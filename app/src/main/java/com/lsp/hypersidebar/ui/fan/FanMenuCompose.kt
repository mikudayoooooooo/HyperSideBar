package com.lsp.hypersidebar.ui.fan

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
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
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 选中态图标放大倍数（PRD §7.3.2"图标放大1.25倍"）；SelectedLabel 避让计算同源。 */
internal const val SELECTED_ICON_SCALE = 1.25f

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
internal fun FanMenuCompose(
    geometry: FanGeometry,
    touchState: MutableState<FanTouchState>,
    colors: FanThemeColors,
    fogIntensity: Float,
    dimEnabled: Boolean,
    source: FanBackdropSource,
    wallpaper: android.graphics.Bitmap?,
    wallpaperOffset: IntOffset,
    exitTick: Int,
    onExitFinished: () -> Unit,
    onAppSelected: (FanAppInfo) -> Unit,
    onQuickAppSelected: (FanAppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val anchor = geometry.anchor
    // 壁纸位图只在「采样壁纸」来源下非空；包装成 ImageBitmap 交给板（remember 保 identity，
    // 池化 composition 逐呼出重建，无跨呼出残留）
    val wallpaperImage = remember(wallpaper) { wallpaper?.asImageBitmap() }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
            FanBoard(
                source = source,
                geometry = geometry,
                colors = colors,
                fogIntensity = fogIntensity,
                wallpaper = wallpaperImage,
                wallpaperOffset = wallpaperOffset,
                sweep = { arcSweep.value }
            )

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
                    fogIntensity = fogIntensity,
                    onQuickAppSelected = onQuickAppSelected
                )
            }
        }
    }
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
    // 选中只放大、不降未选中的透明度：图标本身的可读性优先于"选中更亮"的层级感
    // （真机反馈 0.75 透明度在亮背景上认不出图标）。选中另有高亮板+描边+应用名标签三重提示
    val targetScale = if (isSelected) SELECTED_ICON_SCALE else 1f
    val iconScale by animateFloatAsState(targetValue = targetScale, animationSpec = tween(100))

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
            .scale(iconScale),
        contentAlignment = Alignment.Center
    ) {
        // 选中高亮板仅选中态绘制：常态无底框——原生应用图标自带形状边界，
        // 常驻托底 + 0.7 缩放会造成"双层方框夹空隙"（用户反馈空隙大）
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
            appName = item.app.appName,
            // 0.7（圆形托底时代遗留）→ 0.92：图标几乎占满，与 AllApps 去托底一致
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
            // 描边防隐身：板材质与标签同色系，无边框时标签融进板里（0914 真机反馈）。
            // squircle：surface 外层填充+裁剪，border 内层描边（绘于填充之上）
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
            text = item.app.appName.ifEmpty { item.app.packageName },
            color = colors.onSurface,
            style = MiuixTheme.textStyles.footnote1
        )
    }
}
