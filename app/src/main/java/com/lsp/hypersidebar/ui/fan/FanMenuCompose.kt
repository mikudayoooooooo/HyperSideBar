package com.lsp.hypersidebar.ui.fan

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.layout.ContentScale
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.MutableState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.prefs.LayoutDefaults
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.AccelerateEasing
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 选中态图标放大倍数（PRD §7.3.2"图标放大1.25倍"）；SelectedLabel 避让计算同源。 */
internal const val SELECTED_ICON_SCALE = 1.25f

/**
 * 亚克力噪点图（96px 平铺单元，进程内一次生成）：每像素白/黑 + 随机低 alpha，
 * 平铺后给磨砂板加颗粒 tooth（真机验证前的 textureBlur 版观感主要来自 miuix-blur
 * 的 noise dithering，此处同语义的自绘平铺实现）。dark=深色板用白噪、浅色板用黑噪。
 */
private fun acrylicGrainBitmap(dark: Boolean): android.graphics.Bitmap {
    val side = 96
    val bitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
    val base = if (dark) 0x00FFFFFF else 0x00000000 // RGB 部分：白 or 黑
    val pixels = IntArray(side * side)
    val rng = kotlin.random.Random(0xAC1C)
    for (i in pixels.indices) {
        // 82% 像素全透明、其余随机低 alpha——稀疏颗粒，叠印不脏
        val a = if (rng.nextInt(100) < 18) rng.nextInt(40) else 0
        pixels[i] = base or (a shl 24)
    }
    bitmap.setPixels(pixels, 0, side, 0, 0, side, side)
    return bitmap
}

// ===== 入场=折扇展开（2026-09-13 用户拍板，替代"整体刚性绽放"）=====
// 三通道分层进场：①弧线/雾化沿角度扫开 ②图标按角度次序逐枚从锚点沿半径飞出
// （各自过冲弹簧+级联延迟）③快捷栏弧开后上滑淡入。命中测试始终用终位（几何与
// 动画解耦），入场期间预选/启动不受影响。弹簧/缓动延续 miuix 弹层语系。
private val ArcSweepSpec = tween<Float>(durationMillis = 230, easing = DecelerateEasing(1.5f))
private val IconFlySpec = spring(
    dampingRatio = 0.75f, stiffness = 700f, visibilityThreshold = 0.0001f
)
private val IconAlphaSpec = tween<Float>(durationMillis = 90)
private val ContentAlphaSpec = tween<Float>(durationMillis = 150)
private val QuickEnterSpec = tween<Float>(durationMillis = 200, easing = DecelerateEasing(1.5f))
private val EnterDimSpec = tween<Float>(durationMillis = SCRIM_FADE_MS, easing = SinOutEasing)

// ===== 收拢（exit）：内容层缩向锚点+淡出、scrim 同步淡出，完成回调后宿主摘窗 =====
// 加速缓动（收=渐快离场）；时长=离场观感与跟手性的折中（交互零延迟：窗口即置不可摸）
private val ExitScaleSpec = tween<Float>(durationMillis = 150, easing = AccelerateEasing(1.5f))
private val ExitAlphaSpec = tween<Float>(durationMillis = 120, easing = AccelerateEasing(1.5f))
private val ExitDimSpec = tween<Float>(durationMillis = 150, easing = SinOutEasing)

/** 图标级联总跨度（按角度归一化分摊）：末枚起飞 ≈120ms，全程 ~320ms 内收束 */
private const val ICON_STAGGER_SPAN_MS = 120L
private const val QUICK_ENTER_DELAY_MS = 130L

/** 快捷栏上滑入场距离 */
private const val QUICK_ENTER_SLIDE_DP = 20

/** 收拢终态缩放（缩向锚点，不到 0——配合淡出足够，也避免极端小尺度渲染开销） */
private const val EXIT_SCALE_FLOOR = 0.55f

/** scrim 淡入时长（独立常量：后续做"压暗过渡"设置项时直接暴露此值） */
private const val SCRIM_FADE_MS = 300

@Composable
fun FanMenuCompose(
    geometry: FanGeometry,
    touchState: MutableState<FanTouchState>,
    colors: FanThemeColors,
    fogIntensity: Float,
    dimEnabled: Boolean,
    frosted: Boolean,
    wallpaper: android.graphics.Bitmap?,
    exitTick: Int,
    onExitFinished: () -> Unit,
    onAppSelected: (FanAppInfo) -> Unit,
    onQuickAppSelected: (FanAppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val anchor = geometry.anchor

    var selectedIndex by remember { mutableIntStateOf(-1) }
    var selectedQuickIndex by remember { mutableIntStateOf(-1) }

    // 入场四通道：内容层快淡入（弧线可见性）+ 弧线扫开 + 快捷栏延迟上滑 + scrim 淡入
    val contentAlpha = remember { Animatable(0f) }
    val arcSweep = remember { Animatable(0f) }
    val quickP = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0f) }
    // 收拢两通道：exitTick 被 host 递增即触发（enterTick 基线=组合进入时刻的 tick，
    // 池化逐呼出重组合 → remember 重置，天然不会误触发）
    val exitScale = remember { Animatable(1f) }
    val exitAlpha = remember { Animatable(1f) }
    val enterTick = remember { exitTick }

    LaunchedEffect(Unit) {
        launch { contentAlpha.animateTo(1f, ContentAlphaSpec) }
        launch { arcSweep.animateTo(1f, ArcSweepSpec) }
        launch { scrimAlpha.animateTo(1f, EnterDimSpec) }
        launch {
            delay(QUICK_ENTER_DELAY_MS)
            quickP.animateTo(1f, QuickEnterSpec)
        }
    }
    LaunchedEffect(exitTick) {
        if (exitTick == enterTick) return@LaunchedEffect
        // 收拢并行三路，全部完成后通知宿主摘窗（宿主世代守卫+兜底定时器各自防泄漏）
        joinAll(
            launch { exitScale.animateTo(EXIT_SCALE_FLOOR, ExitScaleSpec) },
            launch { exitAlpha.animateTo(0f, ExitAlphaSpec) },
            launch { scrimAlpha.animateTo(0f, ExitDimSpec) }
        )
        onExitFinished()
    }

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

    val contentOpaque = frosted || wallpaper != null
    Box(modifier = Modifier.fillMaxSize()) {
        // 壁纸磨砂板（0914 用户拍板"优先 miuix 内部采样"）：壁纸垫底（竖屏 launcher
        // 背后恒为壁纸，像素对齐）→ layerBackdrop 录制 → 板形 Shape 上 textureBlur 采样
        // ——miuix 官方三步，AGSL 高斯+噪点+主题混色（暗色主题压亮防晃眼），零系统 API
        if (wallpaper != null) {
            val backdrop = rememberLayerBackdrop()
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Image(
                    bitmap = wallpaper.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .textureBlur(
                        backdrop = backdrop,
                        shape = boardShape(geometry, densityPx()),
                        blurRadius = 120f,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    colors.surfaceContainer.copy(alpha = 0.45f),
                                    BlurBlendMode.SrcOver
                                )
                            )
                        )
                    )
            )
        }
        // 压暗 scrim（用户开关）：全屏纯黑罩在窗口内容最底层，独立淡入/淡出（不参与
        // 内容层缩放——全屏罩缩放会露出未罩住的边）
        if (dimEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha.value }
                    .background(Color.Black.copy(alpha = LayoutDefaults.FAN_DIM_AMOUNT))
            )
        }
        // 内容层：入场只做可见性淡入；收拢时以锚点为原点缩向手指位置+淡出。
        // lambda 内读 Animatable 状态只重绘本层（GPU 合成，不触发重组）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = contentAlpha.value * exitAlpha.value
                    val es = exitScale.value
                    scaleX = es
                    scaleY = es
                    transformOrigin = TransformOrigin(
                        pivotFractionX = anchor.x / geometry.windowSize.width.coerceAtLeast(1),
                        pivotFractionY = anchor.y / geometry.windowSize.height.coerceAtLeast(1)
                    )
                }
        ) {
            FanBackground(geometry, colors, fogIntensity, frosted) { arcSweep.value }

            geometry.items.forEachIndexed { index, item ->
                FanAppIcon(
                    context = context,
                    item = item,
                    isSelected = index == selectedIndex,
                    iconSize = geometry.iconSize,
                    colors = colors,
                    anchor = anchor,
                    startAngle = geometry.startAngle,
                    spanAngle = geometry.spanAngle
                )
            }

            if (selectedIndex in geometry.items.indices) {
                SelectedLabel(
                    item = geometry.items[selectedIndex],
                    iconSize = geometry.iconSize,
                    colors = colors
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = quickP.value
                        translationY = (1f - quickP.value) * QUICK_ENTER_SLIDE_DP.dp.toPx()
                    }
            ) {
                QuickAppsBar(
                    geometry = geometry,
                    selectedIndex = selectedQuickIndex,
                    colors = colors,
                    onQuickAppSelected = onQuickAppSelected
                )
            }
        }
    }
}

@Composable
private fun FanBackground(
    geometry: FanGeometry,
    colors: FanThemeColors,
    fogIntensity: Float,
    frosted: Boolean,
    sweep: () -> Float
) {
    // 材质浓度映射（0914 用户点破"雾化层本身遮住了模糊效果"）：雾化滑条原是为
    // "无真模糊"设计的替身——板要浓才有磨砂感。毛玻璃模式下系统真模糊在板下，
    // 板只该做淡色层，否则模糊被 0.7 浓度板闷死；非毛玻璃维持原浓度（全靠板自己）
    val veilAlpha = if (frosted) (fogIntensity * 0.55f).coerceAtMost(0.6f)
                    else fogIntensity.coerceAtMost(0.85f)
    val sheenAlpha = fogIntensity * (if (frosted) 0.15f else 0.28f)
    val grainAlpha = (fogIntensity * (if (frosted) 0.9f else 1.4f))
        .coerceIn(0f, if (frosted) 0.35f else 0.55f)
    Box(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        // 亚克力磨砂板（0914 用户拍板"同步之前快捷栏的效果到扇形+在基础上做磨砂/亚克力"）：
        // 旧快捷栏 textureBlur 实际贡献=白混染色+噪点抖动（采样输入是栏背后的透明区，
        // blur 本身没糊到东西）——故材质=①主题高调面底色 ②白色提亮 sheen ③噪点颗粒。
        // 毛玻璃开=板下叠系统 blur-behind（真亚克力）；关=板直接贴在未模糊背景上（仿亚克力）。
        // ①② 画在 6dp blur 羽化层；③噪点独立不羽化层（羽化会吃掉颗粒），按板轮廓裁切
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(6.dp)
        ) {
            // 毛玻璃=纯原生配方（0914 用户拍板"白提亮噪点都不加"）：系统模糊+内容，
            // 板层（veil/sheen）全部不上——任何色板都会盖住真模糊
            if (frosted) return@Canvas
            val sweepP = sweep()
            if (sweepP <= 0.01f) return@Canvas
            val span = geometry.spanAngle * sweepP.coerceAtMost(1f)
            val topLeft = Offset(
                geometry.anchor.x - geometry.outerRadius,
                geometry.anchor.y - geometry.outerRadius
            )
            val arcSize = androidx.compose.ui.geometry.Size(
                geometry.outerRadius * 2,
                geometry.outerRadius * 2
            )
            if (fogIntensity > 0.01f) {
                val veil = colors.surfaceContainerHigh.copy(alpha = veilAlpha)
                drawArc(
                    color = veil,
                    startAngle = geometry.startAngle,
                    sweepAngle = span,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize
                )
                // 连体玻璃板：快捷栏胶囊同材质延伸。高度=真实 Row（icon+上下各 0.25q
                // padding）=1.5q——曾按几何层旧估算 2q 画，底部多出 0.5q 裸板（"栏下一大块变白"）
                val n = minOf(6, geometry.quickApps.size)
                if (n > 0) {
                    val q = geometry.quickIconSize * density
                    val barW = n * q + (n - 1) * q * 0.35f + q
                    val barH = q * 1.5f
                    drawRoundRect(
                        color = veil,
                        topLeft = Offset(geometry.quickBarX, geometry.quickBarY),
                        size = androidx.compose.ui.geometry.Size(barW, barH),
                        cornerRadius = CornerRadius(
                            (geometry.quickIconSize / 2f + 4f) * density,
                            (geometry.quickIconSize / 2f + 4f) * density
                        )
                    )
                }
                // 白色提亮 sheen（=之前快捷栏白混的复刻，固定比例随浓度缩放）：
                // Screen 混合（miuix blur guide 多层混合示例同款）——滤色提亮不压灰，
                // 比普通 SrcOver 更接近玻璃质感；深色板下不至于读作纯压暗
                val sheen = Color.White.copy(alpha = sheenAlpha)
                drawArc(
                    color = sheen,
                    startAngle = geometry.startAngle,
                    sweepAngle = span,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                    blendMode = BlendMode.Screen
                )
                if (n > 0) {
                    val q = geometry.quickIconSize * density
                    val barW = n * q + (n - 1) * q * 0.35f + q
                    val barH = q * 1.5f
                    drawRoundRect(
                        color = sheen,
                        topLeft = Offset(geometry.quickBarX, geometry.quickBarY),
                        size = androidx.compose.ui.geometry.Size(barW, barH),
                        cornerRadius = CornerRadius(
                            (geometry.quickIconSize / 2f + 4f) * density,
                            (geometry.quickIconSize / 2f + 4f) * density
                        ),
                        blendMode = BlendMode.Screen
                    )
                }
                drawArc(
                    color = colors.outline.copy(alpha = 0.18f),
                    startAngle = geometry.startAngle,
                    sweepAngle = span,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = 8.dp.toPx())
                )
            }
        }
        // ③噪点颗粒层（亚克力 tooth）：预生成 96px 平铺噪点图，按板轮廓 clip；
        // 颗粒色随主题（深板白噪/浅板黑噪），浓度随滑条。独立 Canvas 不参与 6dp 羽化
        val grainBitmap = remember { acrylicGrainBitmap(dark = false) }
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val sweepP = sweep()
            if (frosted || sweepP <= 0.01f || fogIntensity <= 0.01f) return@Canvas
            val span = geometry.spanAngle * sweepP.coerceAtMost(1f)
            val arcRect = androidx.compose.ui.geometry.Rect(
                geometry.anchor.x - geometry.outerRadius,
                geometry.anchor.y - geometry.outerRadius,
                geometry.anchor.x + geometry.outerRadius,
                geometry.anchor.y + geometry.outerRadius
            )
            val n = minOf(6, geometry.quickApps.size)
            val q = geometry.quickIconSize * density
            val path = androidx.compose.ui.graphics.Path().apply {
                addArc(arcRect, geometry.startAngle, span)
                if (n > 0) {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            geometry.quickBarX, geometry.quickBarY,
                            geometry.quickBarX + n * q + (n - 1) * q * 0.35f + q,
                            geometry.quickBarY + q * 1.5f,
                            CornerRadius(
                                (geometry.quickIconSize / 2f + 4f) * density,
                                (geometry.quickIconSize / 2f + 4f) * density
                            )
                        )
                    )
                }
            }
            clipPath(path) {
                drawRect(
                    brush = ShaderBrush(
                        // Compose 1.10：TileMode.Repeat 已更名 Repeated
                        ImageShader(grainBitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
                    ),
                    alpha = grainAlpha
                )
            }
        }
        // 毛玻璃板形描边（0914 用户要的"边缘框选效果"）：沿模糊区域同款轮廓
        // （饼∪胶囊并集 Path，FanBackground 本地系与几何同源）画细描边，随 sweep 生长
        if (frosted) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val sweepP = sweep()
                if (sweepP <= 0.01f) return@Canvas
                drawPath(
                    frostOutlinePath(geometry, density, sweepP),
                    color = colors.outline.copy(alpha = 0.55f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
        // 锐利外弧描边：不参与 blur，始终清晰——边界感的锚（随 sweep 同步生长）
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val sweepP = sweep()
            if (sweepP <= 0.01f) return@Canvas
            drawArc(
                color = colors.outline.copy(alpha = 0.45f),
                startAngle = geometry.startAngle,
                sweepAngle = geometry.spanAngle * sweepP.coerceAtMost(1f),
                useCenter = false,
                topLeft = Offset(
                    geometry.anchor.x - geometry.outerRadius,
                    geometry.anchor.y - geometry.outerRadius
                ),
                size = androidx.compose.ui.geometry.Size(
                    geometry.outerRadius * 2,
                    geometry.outerRadius * 2
                ),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
private fun densityPx(): Float = LocalDensity.current.density

/** 板形 Shape（饼∪胶囊并集，sweep=1 终态）——miuix textureBlur 的模糊区域 */
private fun boardShape(geometry: FanGeometry, density: Float) = object : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Generic(frostOutlinePath(geometry, density.density, 1f))
}

/**
 * 板形轮廓 Path（窗口本地系，与 FanBackground/命中测试同源坐标）：扇形饼（随 sweep
 * 生长）∪ 快捷栏胶囊——毛玻璃描边用，Compose Path 版（drawPath 直绘）。
 */
private fun frostOutlinePath(
    geometry: FanGeometry,
    density: Float,
    sweepP: Float
): Path {
    val sector = Path().apply {
        moveTo(geometry.anchor.x, geometry.anchor.y)
        arcTo(
            androidx.compose.ui.geometry.Rect(
                geometry.anchor.x - geometry.outerRadius,
                geometry.anchor.y - geometry.outerRadius,
                geometry.anchor.x + geometry.outerRadius,
                geometry.anchor.y + geometry.outerRadius
            ),
            geometry.startAngle, geometry.spanAngle * sweepP.coerceAtMost(1f), false
        )
        close()
    }
    val n = minOf(6, geometry.quickApps.size)
    if (n <= 0) return sector
    val q = geometry.quickIconSize * density
    val capsule = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                geometry.quickBarX, geometry.quickBarY,
                geometry.quickBarX + n * q + (n - 1) * q * 0.35f + q,
                geometry.quickBarY + q * 1.5f,
                CornerRadius(
                    (geometry.quickIconSize / 2f + 4f) * density,
                    (geometry.quickIconSize / 2f + 4f) * density
                )
            )
        )
    }
    return Path().apply { op(sector, capsule, PathOperation.Union) }
}

@Composable
private fun FanAppIcon(
    context: Context,
    item: FanItemLayout,
    isSelected: Boolean,
    iconSize: Float,
    colors: FanThemeColors,
    anchor: Offset,
    startAngle: Float,
    spanAngle: Float
) {
    val (bitmap, fallbackColor) = rememberAppIcon(context, item.app)
    val density = LocalDensity.current.density
    val pxIconSize = iconSize * density
    val targetScale = if (isSelected) SELECTED_ICON_SCALE else 1f
    val targetAlpha = if (isSelected) 1f else 0.75f
    val iconScale by animateFloatAsState(targetValue = targetScale, animationSpec = tween(100))
    val iconAlpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = tween(100))

    // 折扇展开：本枚图标从锚点沿半径飞向终位（spring 过冲）+ 按角度次序级联起飞。
    // 终位静态布局，飞行用 graphicsLayer 平移（=（终位−锚点）×(p−1)，零三角函数）；
    // 命中测试读几何终位，与动画解耦
    val flyP = remember { Animatable(0f) }
    val flyA = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val norm = ((item.angle - startAngle) / spanAngle.coerceAtLeast(1f)).coerceIn(0f, 1f)
        delay((norm * ICON_STAGGER_SPAN_MS).toLong())
        launch { flyP.animateTo(1f, IconFlySpec) }
        launch { flyA.animateTo(1f, IconAlphaSpec) }
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (item.centerX - pxIconSize / 2f).toInt(),
                    (item.centerY - pxIconSize / 2f).toInt()
                )
            }
            .size(iconSize.dp)
            .graphicsLayer {
                val p = flyP.value
                translationX = (item.centerX - anchor.x) * (p - 1f)
                translationY = (item.centerY - anchor.y) * (p - 1f)
                val s = 0.4f + 0.6f * p
                scaleX = s
                scaleY = s
                alpha = flyA.value
            }
            .scale(iconScale)
            .alpha(iconAlpha),
        contentAlignment = Alignment.Center
    ) {
        // 选中高亮板仅选中态绘制：常态无底框——原生应用图标自带形状边界，
        // 常驻托底 + 0.7 缩放会造成"双层方框夹空隙"（用户反馈空隙大）
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape((iconSize * 0.25f).dp))
                    .background(colors.primaryContainer.copy(alpha = 0.9f))
            )
        }
        AppIconImage(
            bitmap = bitmap,
            fallbackColor = fallbackColor,
            appName = item.app.appName,
            // 0.7（圆形托底时代遗留）→ 0.92：图标几乎占满，与 AllApps 去托底一致
            size = iconSize * 0.92f,
            colors = colors
        )

        if (isSelected) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                // B1：描边随 mask 同形状（圆角方）
                drawRoundRect(
                    color = colors.primary,
                    cornerRadius = CornerRadius(size.minDimension * 0.25f, size.minDimension * 0.25f),
                    style = Stroke(width = 2.dp.toPx())
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
            // 描边防隐身：板材质与标签同色系，无边框时标签融进板里（0914 真机反馈）
            .border(1.dp, colors.outline.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
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
