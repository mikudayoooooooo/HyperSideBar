package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
import androidx.core.view.OneShotPreDrawListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.theme.HyperSidebarTheme
import com.lsp.hypersidebar.theme.ThemeModes
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import com.lsp.hypersidebar.util.CancelZone
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.StatsRecorder

private const val TAG = "ComposeFanHost"

/** 分数迟滞余量：仅当最优项显著优于当前项才切换，杜绝预选抖动翻转。 */
private const val HYSTERESIS_MARGIN = 0.15f

data class FanTouchState(
    val x: Float,
    val y: Float,
    val touchAction: Int,
    val selectedIndex: Int,
    val selectedQuickIndex: Int = -1
)

class ComposeFanHost(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    private var wrapperView: View? = null
    private var composeView: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var lifecycleOwner: FanLifecycleOwner? = null
    private var lastSelectedFanIndex = -1
    private var lastSelectedQuickIndex = -1
    private var selectedSince = 0L
    /** 当前"连续悬停"的起点（手指第一次落到任一图标/快捷项的时刻）。换项不清零。 */
    private var streakSince = 0L
    private val DWELL_MS = 150L
    /**
     * 最后一个图标的最小停留（连续悬停语义的防误触半环）。
     *
     * 2026-10-17 真机实测（OS2 侧滑停顿通道）：手指沿弧轻微漂移会在图标间反复换项
     * （`sel change: 4->3->2->1`，间隔 648/100/85ms），而旧逻辑"每次换项都重置
     * selectedSince"⇒ 150ms 门要等手指**完全停稳**才起算 ⇒ 观感"要比角滑停更久才能打开"。
     * 现改为：换项只重置 [selectedSince]（本项停留），[streakSince] 持续累计 ⇒
     * 只要整体悬停够 150ms 且最终项停留 ≥ 本值即启动（快速扫过多项仍被这一项拦住）。
     */
    private val FINAL_MIN_MS = 60L

    // ===== 池化复用（1C P2，方案对比后选"池=1 实例重配"）=====
    // composition/lifecycle/视图树跨呼出存活（只摘窗口），把暖呼出从 ~250-300ms
    // 压到 <100ms（重建 composition 的成本消失）。拒绝"常驻隐藏窗口"方案：
    // 本项目根基就是消灭常驻覆盖窗口（小白条之祸），不能自己再造一个。
    private var built = false
    private val geometryState: MutableState<FanGeometry?> = mutableStateOf(null)
    private val touchState: MutableState<FanTouchState> =
        mutableStateOf(FanTouchState(0f, 0f, 3, -1))
    private var config: FanConfig = FanConfig()
    private var density = 1f
    private var pendingInput: GeometryInput? = null

    // ===== 收拢动画协议（2026-09-13，用户拍板"加收拢动画"）=====
    // dismiss 不再同步摘窗：递增 exitTick → 组合内三通道回 0（缩向锚点+淡出+scrim 淡出）
    // → onExitFinished 回调摘窗。三道防线保证"不影响正常使用"：
    // ① 收拢启动即 FLAG_NOT_TOUCHABLE（updateViewLayout）——150ms 内触摸全穿透，零拦截；
    // ② attachGen 世代守卫——收拢中 re-show bump 世代，旧摘窗回调/兜底全部失效；
    // ③ 兜底定时器——组合死亡/协程丢失时 400ms 强制摘窗（守卫幂等）
    private val exitTickState: MutableState<Int> = mutableStateOf(0)
    private var attachGen = 0
    private var pendingExitGen = -1
    private var currentParams: WindowManager.LayoutParams? = null

    /** 本次呼出的背景模糊来源（show() 求值；preDraw/命中测试/渲染按此分派） */
    private var blurSource: FanBackdropSource = FanBackdropSource.NONE

    /** 本次呼出的壁纸位图（仅「采样壁纸」来源非空：竖屏 launcher 且缓存就绪） */
    private var wallpaperBitmap: android.graphics.Bitmap? = null

    /**
     * 壁纸对位（0921）：窗口原点在**屏幕坐标**中的位置。采样壁纸是屏幕的等比居中等比裁剪，
     * 只有按屏幕还原再平移这个原点，磨砂里的"背后"才与桌面真实壁纸像素对得上。
     * 逐呼出在 [computeAndPublishGeometry] 里从 getLocationOnScreen 实测（窗口比屏幕矮时非 0）。
     */
    private var wallpaperOffset: androidx.compose.ui.unit.IntOffset =
        androidx.compose.ui.unit.IntOffset.Zero

    /** 屏幕像素尺寸（壁纸应铺满的范围）；show() 里从 displayMetrics 读，供壁纸对位换算。 */
    private var wallpaperDisplay: androidx.compose.ui.unit.IntSize =
        androidx.compose.ui.unit.IntSize.Zero

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private companion object {
        /** 收拢兜底摘窗（正常收拢 ~150ms；覆盖组合卡顿余量） */
        const val EXIT_FALLBACK_MS = 400L

        /** 预热装配窗口保持时长：覆盖首帧绘制+JIT 稳定，之后摘窗（池内 composition 保留） */
        const val WARMUP_HOLD_MS = 600L
    }

    /**
     * 空闲预热装配（2026-09-13 横屏游戏首呼出动画卡顿）：1×1 离屏窗口（屏外 1 像素、
     * NOT_TOUCHABLE，不可见不可摸）完成 Compose 运行时初始化+主题+内容树组合+首帧绘制
     * （含 blur RenderEffect 着色器首编译）后摘窗。池内 composition/lifecycle 保留，
     * 真呼出走 built=true 快路径——首呼出不再吃一次性装配成本。世代守卫：保持期内
     * 真 show() 到来则照常 detach+世代自增，本预热摘窗自动失效。
     */
    fun warmup(context: Context, apps: List<FanAppInfo>, quickApps: List<FanAppInfo>) {
        if (built) return
        density = context.resources.displayMetrics.density
        config = buildFanConfig()
        val dm = context.resources.displayMetrics
        // 锚点取屏心（纯占位——预热只求组合/绘制管线跑通，几何正确性无关紧要）
        pendingInput = GeometryInput(
            dm.widthPixels * 0.5f, dm.heightPixels * 0.5f, apps, quickApps, false
        )
        resetInteractionState()
        buildComposition()
        built = true
        val wrapper = wrapperView ?: return
        if (wrapper.isAttachedToWindow) detachWindow()
        attachGen++
        val params = buildWindowParams().apply {
            width = 1
            height = 1
            x = -4
            y = -4
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        currentParams = params
        val gen = attachGen
        try {
            windowManager?.addView(wrapper, params)
        } catch (e: Throwable) {
            HLog.w(TAG, "warmup attach failed: ${e.message}")
            detachWindow()
            return
        }
        OneShotPreDrawListener.add(wrapper) { computeAndPublishGeometry(); true }
        wrapper.post {
            mainHandler.postDelayed({
                if (gen == attachGen) {
                    HLog.i(TAG, "warmup: detach after assembly (gen=$gen)")
                    detachWindow()
                }
            }, WARMUP_HOLD_MS)
        }
        HLog.i(TAG, "warmup: 1x1 offscreen assembly attached (gen=$gen), ${apps.size} apps, ${quickApps.size} quick")
    }

    // 窗口在屏上的原点（每手势 DOWN 刷新）：命中测试必须与渲染同处窗口本地坐标系。
    // 若 overlay 窗口被系统 inset（让出状态栏等），raw 屏幕坐标与本地坐标会差出
    // 一个状态栏高度（实测≈110px），"指到的图标"与"命中的扇区"系统性错一位
    private var originValid = false
    private val viewOrigin = IntArray(2)

    var onAppSelected: ((FanAppInfo) -> Unit)? = null
    var onQuickAppSelected: ((FanAppInfo) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    /** 常驻点选态（fanPersistentOnRelease，13.1 同批新增）：由 [FanMenuController] 在
     *  "手势结束未提交选中"或"装配中已松手"时置位。released 态下：UP 提交跳过 150ms
     *  预选门（点按节奏远快于滑选），DOWN 未命中任何图标=点空白 → onOutsideTap 收起。 */
    @Volatile private var released = false
    var onOutsideTap: (() -> Unit)? = null

    // ===== 组展开态（C7，计划 §B4；无组数据时全链休眠=今日行为）=====
    /** 当前快捷栏是否含组条目（show 时刷新）——true 时触摸走 QuickExpandMachine 路径 */
    private var quickHasGroups = false
    private var expandState = QuickExpandMachine.State()
    /** 展开前的根栏输入 stash（收回时还原） */
    private var rootQuickStash: GeometryInput? = null
    /** 展开子栏的锚定中心 X（原组图标中心；null=未展开）——传给 computeFanGeometry */
    private var expandedAnchorCenterX: Float? = null

    /**
     * 孤儿 UP 防护（OS4 借道路径实测，2026-10-04）：
     *
     * p7 借道路径下扇窗是在**用户手指仍在屏上**时打开的（该手势的 DOWN 已被系统/launcher
     * 消费，扇窗永远拿不到配对 DOWN）。手指抬起时这条流的 UP 会落到扇窗上，`released`
     * 点选态 + 距锚点很远 ⇒ 被判成"点空白"⇒ `TAP_OUTSIDE` 立即收起。
     * 实测三连：show 后 0.64~0.80s 必收，UP `dist=810/1145/782`、`dwell=0` ⇒ 观感是
     * "扇形一闪就没、也拖不动"。
     *
     * 判据：本窗口会话内**没见过 DOWN**、且 `event.downTime` 早于本窗展示时刻 ⇒
     * 该事件属于呼出前那条手势的尾巴，直接吞掉，不参与选中/点空白判定。
     * 真实点空白或窗口内滑动必然先有自己的 DOWN（downTime ≥ 展示时刻）⇒ 不受影响。
     */
    private var sawDownInSession = false
    private var sessionDownTime = 0L
    private var shownUptime = 0L

    fun setReleased(v: Boolean) {
        released = v
    }

    private class GeometryInput(
        val anchorX: Float,
        val anchorY: Float,
        val apps: List<FanAppInfo>,
        val quickApps: List<FanAppInfo>,
        val isLandscape: Boolean,
        val cornerAnchor: Boolean = false
    )

    fun show(
        anchorX: Float,
        anchorY: Float,
        apps: List<FanAppInfo>,
        quickApps: List<FanAppInfo>,
        isLandscape: Boolean,
        cornerAnchor: Boolean = false
    ) {
        val wm = windowManager
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .also { windowManager = it }
        density = context.resources.displayMetrics.density
        wallpaperDisplay = androidx.compose.ui.unit.IntSize(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels
        )
        config = buildFanConfig()
        // 诊断（迭代二 P5）：呼出时回显实际读到的配置值——对照滑条改动可判定
        // hook 侧 prefs 是否实时同步（stale=快照不更新）
        HLog.i(
            TAG,
            "config: icon=${config.iconSizeDp} inner=${config.innerRadiusDp}d outer=${config.outerRadiusDp}d " +
                "dead=${config.deadZoneDp}d outerN=${config.maxAppsOuter} innerN=${config.maxAppsInner} landscape=$isLandscape " +
                "fog=${readFloat(PrefKeys.FAN_FOG_INTENSITY, LayoutDefaults.FAN_FOG_INTENSITY)} " +
                "dim=${readBoolean(PrefKeys.FAN_DIM_ENABLED, LayoutDefaults.FAN_DIM_ENABLED)}"
        )
        pendingInput = GeometryInput(anchorX, anchorY, apps, quickApps, isLandscape, cornerAnchor)
        quickHasGroups = quickApps.any { it.group != null }
        resetInteractionState()
        // 壁纸磨砂（0914 用户拍板"优先 miuix 内部采样"）：launcher 与 :ui 双宿主同接、
        // 共用同一张壁纸图（0915 用户拍板）——竖屏桌面采样内容=真实背景；横屏 :ui 垫的
        // 也是壁纸（内容非游戏画面，用户接受）。位图由 WallpaperSampler 在 init 空闲期
        // 预载，peek 零 binder；冷缓存退亚克力
        if (context.packageName == "com.miui.home" ||
            context.packageName == "com.miui.securitycenter"
        ) {
            com.lsp.hypersidebar.util.WallpaperSampler.refreshIfStale(context)
            wallpaperBitmap = com.lsp.hypersidebar.util.WallpaperSampler.peek()
        }

        // 耗时锚点（呼出卡顿归因）：firstBuild=首次装配（Compose 运行时类加载+首次组合，
        // 项目实测 ~250-300ms）；addView=窗口创建 binder+首帧前成本，每次呼出都发生
        val firstBuild = !built
        if (!built) {
            buildComposition()
            built = true
        }
        val wrapper = wrapperView ?: return
        try {
            // 防御：池化后理论上 dismiss 必摘窗口，但 compose 内部 onDismiss 等路径
            // 若留下挂载态，重复 addView/setContentView 会直接抛——先收敛到摘除态
            if (wrapper.isAttachedToWindow) detachWindow()
            val tAddMs = SystemClock.elapsedRealtime()
            // 世代自增：在场的收拢回调/兜底定时器全部失效（收拢中再呼出=打断收拢直接重开）
            attachGen++
            // 背景模糊来源解析（Route D 0915 定稿：单项下拉，取代旧三开关互斥）。两条通道：
            //  · 采样壁纸——miuix 内部采样，板材质完整生效，形状任意（横竖屏共用同一张壁纸）
            //  · 后方屏幕全屏景深——FLAG_BLUR_BEHIND（MIUI 实现为全屏糊，非局部）
            // 另有「透明」（不取背后内容也不加材质）与「关闭」（板自身即材质）两档。
            // 失效则降级：所选来源不可用时退无来源，板永远有材质。
            // 注：原「系统裁剪模糊（Dialog 背景模糊）」已退役——AOSP 背景模糊区域恒等于窗口
            // 矩形，与楔形板形必然打架，只能给出一个圆角大矩形，用户否决。
            val crossBlur = windowManager?.isCrossWindowBlurEnabled ?: false
            blurSource = resolveBlurSource(
                pref = readString(PrefKeys.FAN_BLUR_SOURCE, LayoutDefaults.FAN_BLUR_SOURCE_DEFAULT),
                crossWindowBlurEnabled = crossBlur,
                wallpaperReady = wallpaperBitmap != null
            )
            // 壁纸对位的窗口原点不在这里清零：由 computeAndPublishGeometry 在挂窗后实测覆盖
            //（首帧绘制前，晚于本处；见该函数注释）
            val params = buildWindowParams()
            if (blurSource == FanBackdropSource.BEHIND_SCREEN) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                // 半径单位 px（AOSP 建议 20px 起、上限 150px），不乘 density
                params.blurBehindRadius = LayoutDefaults.FAN_BEHIND_BLUR_RADIUS_PX.toInt()
            }
            currentParams = params
            // 新会话：本窗展示时刻 + 未见过 DOWN（孤儿 UP 防护的判据基线，见 sawDownInSession）
            shownUptime = SystemClock.uptimeMillis()
            sawDownInSession = false
            sessionDownTime = 0L
            wm.addView(wrapper, params)
            HLog.i(TAG, "addView: ${SystemClock.elapsedRealtime() - tAddMs}ms source=$blurSource")
        } catch (e: Throwable) {
            HLog.e(TAG, "Failed to attach fan window", e)
            detachWindow()
            throw e // 上抛给 controller：失败可观测（熔断计数）并弃池
        }
        // 首帧绘制前算几何（origin-before-geometry，1B）：悬浮窗被系统 inset 后
        // 真实原点/尺寸只有布局后才可知。池化后视图多次 attach，OneShot 逐 show 重挂
        OneShotPreDrawListener.add(wrapper) { computeAndPublishGeometry(); true }
        HLog.i(TAG, "fan window attached (pooled=$built, firstBuild=$firstBuild), ${apps.size} apps, ${quickApps.size} quick")
    }

    /**
     * 背景模糊来源解析（Route D 0915）。auto = 按能力自动选路，优先级体现「哪条路
     * 能做出完整的 miuix 板」：壁纸就绪 → 采样壁纸（唯一拿得到真像素、模糊/混色/
     * 噪点全部生效、形状任意；横竖屏共用同一张图，0915 用户拍板）→ 否则无来源
     * （板自身即材质，最稳）。显式指定失效时同样降级。
     */
    private fun resolveBlurSource(
        pref: String,
        crossWindowBlurEnabled: Boolean,
        wallpaperReady: Boolean
    ): FanBackdropSource = when (pref) {
        // auto 与 wallpaper 现行为一致：壁纸就绪即采样（横竖屏皆可）
        PrefKeys.FAN_BLUR_SOURCE_AUTO,
        PrefKeys.FAN_BLUR_SOURCE_WALLPAPER ->
            if (wallpaperReady) FanBackdropSource.WALLPAPER else FanBackdropSource.NONE

        PrefKeys.FAN_BLUR_SOURCE_BEHIND ->
            if (crossWindowBlurEnabled) FanBackdropSource.BEHIND_SCREEN
            else FanBackdropSource.NONE

        PrefKeys.FAN_BLUR_SOURCE_TRANSPARENT -> FanBackdropSource.TRANSPARENT

        PrefKeys.FAN_BLUR_SOURCE_OFF -> FanBackdropSource.NONE

        // 非法/未知取值：按默认档解析（单一来源，改默认无需改这里；递归必达已知取值）
        else -> resolveBlurSource(
            LayoutDefaults.FAN_BLUR_SOURCE_DEFAULT,
            crossWindowBlurEnabled,
            wallpaperReady
        )
    }

    /** 逐呼出重置交互态（几何清空 → 首帧前不渲染，touch/选中态归零）。 */
    private fun resetInteractionState() {
        geometryState.value = null
        touchState.value = FanTouchState(0f, 0f, 3, -1)
        lastSelectedFanIndex = -1
        lastSelectedQuickIndex = -1
        selectedSince = 0L
        originValid = false
        // 组展开态随呼出清零（§6 断言：重复呼出 20 次无展开残留）
        expandState = QuickExpandMachine.State()
        rootQuickStash = null
        expandedAnchorCenterX = null
    }

    private fun computeAndPublishGeometry() {
        val input = pendingInput ?: return
        val wrapper = wrapperView ?: return
        val loc = IntArray(2)
        runCatching { wrapper.getLocationOnScreen(loc) }
        // 壁纸对位：把实测窗口原点交给渲染层（采样壁纸按屏幕还原后再平移这个原点）。
        // 必须写在 geometryState 赋值之前——这个字段不是 state，靠这次 geometry 变更驱动重组时被读到
        wallpaperOffset = androidx.compose.ui.unit.IntOffset(loc[0], loc[1])
        // 窗口恒为全屏 overlay：边距自适应直接用窗口本地系（锚点减去窗口实际原点）
        val g = computeFanGeometry(
            Offset(input.anchorX - loc[0], input.anchorY - loc[1]),
            IntSize(wrapper.width, wrapper.height),
            input.apps, input.quickApps, config, density, input.isLandscape,
            input.cornerAnchor,
            // 组展开锚定（C6 参数）：子栏以原组图标中心排布，几何连续无平移跳变
            quickBarCenterX = expandedAnchorCenterX
        )
        geometryState.value = g
        HLog.i(
            TAG,
            "geometry: origin=(${loc[0]},${loc[1]}) winSize=(${wrapper.width},${wrapper.height}) " +
                "rawAnchor=(${input.anchorX.toInt()},${input.anchorY.toInt()}) anchor=(${g.anchor.x.toInt()},${g.anchor.y.toInt()}) " +
                "outer=${g.outerRadius.toInt()} inner=${g.innerRadius.toInt()} " +
                "span=[${g.startAngle.toInt()},${g.endAngle.toInt()}] icon=${g.iconSize} " +
                "quickBar=(${g.quickBarX.toInt()},${g.quickBarY.toInt()})"
        )
    }

    /** composition/视图树一次性构建（池化后不再重建）。 */
    private fun buildComposition() {
        val lcOwner = FanLifecycleOwner()
        lifecycleOwner = lcOwner
        lcOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lcOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lcOwner)
            setViewTreeSavedStateRegistryOwner(lcOwner)
            setContent {
                FanMenuWithTheme {
                    val themeColors = extractFanThemeColors()
                    // 几何未就绪（首帧布局前）不渲染任何内容——无错位闪烁；
                    // 状态就绪后重组出现扇形（晚一帧，~16ms 不可感知）
                    geometryState.value?.let { g ->
                        FanMenuCompose(
                            geometry = g,
                            touchState = touchState,
                            colors = themeColors,
                            // 路线 C 视觉参数：逐呼出随重组重读（几何状态变化驱动），
                            // 与 extractFanThemeColors 同一读取模式
                            fogIntensity = readFloat(
                                PrefKeys.FAN_FOG_INTENSITY, LayoutDefaults.FAN_FOG_INTENSITY
                            ),
                            dimEnabled = readBoolean(
                                PrefKeys.FAN_DIM_ENABLED, LayoutDefaults.FAN_DIM_ENABLED
                            ),
                            scrimFadeMs = readInt(
                                PrefKeys.FAN_SCRIM_FADE_MS, LayoutDefaults.FAN_SCRIM_FADE_MS
                            ),
                            source = blurSource,
                            wallpaper = if (blurSource == FanBackdropSource.WALLPAPER) wallpaperBitmap else null,
                            wallpaperOffset = wallpaperOffset,
                            wallpaperDisplay = wallpaperDisplay,
                            exitTick = exitTickState.value,
                            onExitFinished = { finishExitFromCompose() },
                            onAppSelected = { app -> onAppSelected?.invoke(app) },
                            onQuickAppSelected = { app -> onQuickAppSelected?.invoke(app) },
                            // compose 内部请求收起 → 走同一 dismiss 语义（收拢+摘窗+通知 controller）
                            onDismiss = { dismiss() }
                        )
                    }
                }
            }
        }
        this.composeView = composeView

        val wrapper = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val rawX = event.rawX
                val rawY = event.rawY
                // 窗口原点带有效性持续重试：DOWN 可能早于布局完成（getLocationOnScreen
                // 返回 0），每个事件都重试直到捕获有效原点（attach 且有宽度）
                if (!originValid && isAttachedToWindow && width > 0) {
                    runCatching { getLocationOnScreen(viewOrigin) }
                    originValid = true
                    HLog.i(TAG, "fan origin=(${viewOrigin[0]},${viewOrigin[1]}) size=(${width},${height})")
                }
                // 几何未就绪（首帧布局前的 ~1 帧）：消费事件不解析——屏上无渲染，无图标
                // 可命中；事件归属本手势，漏给下层应用会成幽灵触摸
                val geometry = geometryState.value ?: return true
                val density = context.resources.displayMetrics.density
                // 行为规则 4：实际死区 = max(deadZone×density, innerRadius×0.08)，上限 60px；
                // 圆心 = 锚点（几何层不平移，PRD §9.5），选区计算直接用 geometry.anchor
                // （与渲染同一坐标源）
                val deadZonePx = maxOf(config.deadZoneDp * density, geometry.innerRadius * 0.08f).coerceAtMost(60f)
                val innerCancelPx = ((geometry.innerRadius * 0.85f - geometry.iconSize * density * 0.5f) * 0.75f)
                val outerCancelPx = ((geometry.outerRadius + geometry.iconSize * density * 0.5f) * 1.25f)
                val ax = geometry.anchor.x
                val ay = geometry.anchor.y
                val x = rawX - viewOrigin[0]
                val y = rawY - viewOrigin[1]
                val dx = x - ax
                val dy = y - ay
                val dist = sqrt(dx * dx + dy * dy)
                // 孤儿手势防护（OS4 借道路径，2026-10-04 实测）：本窗展示前就已开始的那条手势，
                // 其余下事件会迟到地落到刚呼出的扇窗上，点选态下被当成"点空白"立即收起
                // （实测 show 后 0.64~1.2s 必收）。两种投递形态都要挡：
                //   ① 只收到 UP/CANCEL（DOWN 被系统消费掉了）；
                //   ② 先收到一个**带着原始 downTime 的重新投递 DOWN**（新窗口出现时的标准
                //      行为），再收到同 downTime 的 UP。
                // 统一判据：`event.downTime < shownUptime` = 该事件属于呼出前的手势。
                // 真实点选/窗内滑动必然从本窗展示之后开始（downTime ≥ shownUptime）⇒ 不受影响。
                // B2 流驱动旁路：转发流的 DOWN/UP 恒满足 preShowGesture（呼出发生在按住
                // 期间）⇒ 旗标置位期间放行，否则拖选永无法提交（1005 15:54 导出实锤）。
                // 窗体自身收到的原始尾巴不带旗标 ⇒ 照旧被吞（两条投递路径就此分离）。
                val preShowGesture = shownUptime != 0L && event.downTime < shownUptime &&
                    !B2StreamDispatch.active
                if (preShowGesture) {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        // 形态 ② 的起始：不承认它，避免后续 UP 被算成一次"点空白"
                        return true
                    }
                    if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                        HLog.i(
                            TAG,
                            "orphan ${if (event.action == MotionEvent.ACTION_UP) "UP" else "CANCEL"} swallowed " +
                                "(pre-show gesture tail): downTime=${event.downTime} shown=$shownUptime " +
                                "raw=(${rawX.toInt()},${rawY.toInt()}) dist=${dist.toInt()}"
                        )
                        return true
                    }
                }
                if (event.action == MotionEvent.ACTION_DOWN) {
                    sawDownInSession = true
                    sessionDownTime = event.downTime
                }
                // 组触摸路径（C7，计划 §B4）：快捷栏含组（或已展开）时，quick 交互整体走
                // QuickExpandMachine——含普通项的选中/计时/提交（与组共用一套状态）；
                // fan 侧选中仍用 resolveFanSelection 保持滞回。无组时本路径休眠=今日行为
                if ((quickHasGroups || expandState.expanded) &&
                    handleGroupTouch(event, geometry, x, y, dx, dy, dist, deadZonePx)
                ) {
                    return true
                }
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastSelectedFanIndex = -1
                        lastSelectedQuickIndex = -1
                        selectedSince = 0L
                        streakSince = 0L
                        // 采集层 v2：DOWN 即起点，位移从这一刻开始累加
                        StatsRecorder.onFanMove(x, y)
                        val (fanSel, quickSel) = resolveSelection(
                            x, y, dx, dy, dist, deadZonePx, geometry
                        )
                        // 常驻点选态：未命中任何图标/快捷项=点空白 → 收起并吞掉该次点击
                        //（用户拍板"点外收起+吞点击"，防误触底层）。窗口全屏，常驻期间
                        // 底层收不到任何触摸；收起后下一次触摸自然回归底层。
                        if (released && fanSel == -1 && quickSel == -1) {
                            Log.d(TAG, "persistent tap outside → dismiss")
                            touchState.value = FanTouchState(x, y, 2, -1, -1)
                            onOutsideTap?.invoke()
                            return true
                        }
                        if (fanSel != -1 || quickSel != -1) {
                            val t = SystemClock.uptimeMillis()
                            selectedSince = t
                            streakSince = t
                            StatsRecorder.onFanPreselected()
                        }
                        touchState.value = FanTouchState(x, y, 0, fanSel, quickSel)
                        Log.d(TAG, "touch DOWN fan=$fanSel quick=$quickSel dist=${dist.toInt()}")
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        StatsRecorder.onFanMove(x, y)
                        val prevFan = lastSelectedFanIndex
                        val prevQuick = lastSelectedQuickIndex

                        // 外取消豁免（迭代四 §1.1）：快捷栏在锚点 712px 外本就落在外取消
                        // 半径之外，栏上滑动会被 CLEAR/重命中振荡清掉 dwell 致点按不启动
                        // （1C 实测）。命中圈(0.8 图标距)覆盖栏间隙，栏外空白仍正常取消；
                        // 内取消（滑回锚点）不豁免
                        val inCancelZone = dist < innerCancelPx ||
                            (dist > outerCancelPx && calcQuickAppCandidate(x, y, geometry) == -1)
                        if (inCancelZone && (prevFan != -1 || prevQuick != -1)) {
                            lastSelectedFanIndex = -1
                            lastSelectedQuickIndex = -1
                            selectedSince = 0L
                            streakSince = 0L
                            touchState.value = FanTouchState(x, y, 0, -1, -1)
                            Log.d(TAG, "touch CLEAR by cancel zone (inner=${innerCancelPx.toInt()}, outer=${outerCancelPx.toInt()})")
                            return true
                        }

                        val (fanSel, quickSel) = resolveSelection(
                            x, y, dx, dy, dist, deadZonePx, geometry
                        )

                        if (fanSel != prevFan || quickSel != prevQuick) {
                            Log.d(TAG, "sel change: fan $prevFan->$fanSel quick $prevQuick->$quickSel dist=${dist.toInt()}")
                        }

                        val anySelected = fanSel != -1 || quickSel != -1
                        val prevSelected = prevFan != -1 || prevQuick != -1
                        when {
                            !anySelected -> {      // 选中变空 ⇒ 整段连续悬停结束
                                selectedSince = 0L
                                streakSince = 0L
                            }
                            !prevSelected -> {     // 从空白进入图标：新一段连续悬停
                                val t = SystemClock.uptimeMillis()
                                selectedSince = t
                                streakSince = t
                                StatsRecorder.onFanPreselected()
                            }
                            fanSel != prevFan || quickSel != prevQuick -> {
                                // 换项：只重置"本项停留"，连续悬停总计时继续累计
                                selectedSince = SystemClock.uptimeMillis()
                                StatsRecorder.onFanPreselected()
                            }
                        }

                        touchState.value = FanTouchState(x, y, 0, fanSel, quickSel)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val (fanSel, quickSel) = resolveSelection(
                            x, y, dx, dy, dist, deadZonePx, geometry
                        )
                        // 采集层 v2：UP 现场记取消区与松手时刻（口径要"松手时指尖在哪"，事后推不出）
                        StatsRecorder.onFanUp(
                            when {
                                dist < deadZonePx -> CancelZone.DEAD
                                dist < innerCancelPx -> CancelZone.INNER
                                dist > outerCancelPx -> CancelZone.OUTER
                                else -> CancelZone.IN_BAND
                            },
                            deadZonePx
                        )
                        touchState.value = FanTouchState(x, y, 2, -1, -1)
                        // 取证 dump：本地/原始坐标 + 极坐标 + 命中项全量，选中错位一轮日志定位
                        val deg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
                        // selectedSince==0 = 手势期间从未预选过（快速扫过即松手），dwell 按 0 计——
                        // 顺带修真 bug：此前 uptime−0 为巨大垃圾值，会让 <150ms 快速松手也通过
                        // 预选时长检查（违反 PRD"预选不足 150ms 松手不启动"）
                        val nowUp = SystemClock.uptimeMillis()
                        val dwellMs = if (selectedSince == 0L) 0L else nowUp - selectedSince
                        val streakMs = if (streakSince == 0L) 0L else nowUp - streakSince
                        val hitItem = geometry.items.getOrNull(fanSel)
                        HLog.i(
                            TAG,
                            "UP resolve: local=(${x.toInt()},${y.toInt()}) raw=(${rawX.toInt()},${rawY.toInt()}) " +
                                "dist=${dist.toInt()} deg=${"%.1f".format(deg)} fan=$fanSel" +
                                (hitItem?.let {
                                    " [${it.app.packageName} ang=${"%.1f".format(it.angle)} rad=${it.radius.toInt()} ctr=(${it.centerX.toInt()},${it.centerY.toInt()})]"
                                } ?: "") +
                                " quick=$quickSel dwell=$dwellMs streak=$streakMs"
                        )

                        val dwellTime = dwellMs
                        val anySelected = fanSel in geometry.items.indices
                        val anyQuick = quickSel in geometry.quickApps.indices
                        // 常驻点选态：跳过预选门——点按的 DOWN→UP 间隔远小于滑选预选时长，
                        // 门槛会把"明明点中了"判成"dwell too short"拒启动
                        val commitGate = if (released) 0L else DWELL_MS
                        val finalGate = if (released) 0L else FINAL_MIN_MS
                        val passedDwell = dwellTime >= commitGate && streakMs >= commitGate &&
                            dwellTime >= finalGate

                        when {
                            !anySelected && !anyQuick -> {
                                handleQuickBarTap(x, y, geometry, config, density)
                            }
                            anySelected && !passedDwell -> {
                                Log.d(TAG, "dwell too short: item=${dwellTime}ms streak=${streakMs}ms (need $commitGate/$finalGate), not launching")
                            }
                            anyQuick && !passedDwell -> {
                                Log.d(TAG, "dwell too short: item=${dwellTime}ms streak=${streakMs}ms (need $commitGate/$finalGate), not launching")
                            }
                            anySelected -> {
                                HLog.i(TAG, "selected fan: ${geometry.items[fanSel].app.packageName}")
                                onAppSelected?.invoke(geometry.items[fanSel].app)
                            }
                            anyQuick -> {
                                HLog.i(TAG, "selected quick: ${geometry.quickApps[quickSel].packageName}")
                                onQuickAppSelected?.invoke(geometry.quickApps[quickSel])
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touchState.value = FanTouchState(x, y, 3, -1, -1)
                        lastSelectedFanIndex = -1
                        lastSelectedQuickIndex = -1
                    }
                }
                return super.dispatchTouchEvent(event)
            }
        }
        wrapper.addView(composeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        wrapper.setViewTreeLifecycleOwner(lcOwner)
        wrapper.setViewTreeSavedStateRegistryOwner(lcOwner)
        this.wrapperView = wrapper
    }

    private fun buildWindowParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

    /** 摘窗口并复位交互态（不动 composition/lifecycle——池化复用的前提）。 */
    private fun detachWindow() {
        val wv = wrapperView ?: return
        try {
            windowManager?.removeViewImmediate(wv)
        } catch (e: Throwable) {
            // "not attached"= 窗口已不在（等价摘除成功）；其余异常窗口同样已脱离
            // 本进程管理——都按"摘除已确认"处理
            HLog.w(TAG, "removeView failed (treated as detached): ${e.message}")
        }
        resetInteractionState()
    }

    fun dismiss() {
        val wv = wrapperView
        // 无内容可收拢（未挂载/几何未出）或未构建：维持旧瞬时语义，直接摘
        if (wv == null || !built || !wv.isAttachedToWindow || geometryState.value == null) {
            detachWindow()
            onDismiss?.invoke()
            return
        }
        // 收拢三防线之一：触摸立即穿透（交互零延迟，画面再收 150ms）
        setWindowNotTouchable()
        val gen = attachGen
        pendingExitGen = gen
        exitTickState.value = exitTickState.value + 1
        // 收拢三防线之三：兜底摘窗（组合死亡/协程丢失；世代守卫幂等，正常路径先到先摘）
        mainHandler.postDelayed({ teardownIfCurrent(gen) }, EXIT_FALLBACK_MS)
        HLog.i(TAG, "dismiss: exit animation requested (gen=$gen)")
    }

    /** 组合内收拢动画完成回调 → 摘窗（世代不符=收拢中已被 re-show 打断，忽略）。 */
    private fun finishExitFromCompose() {
        teardownIfCurrent(pendingExitGen)
    }

    /** 世代守卫摘窗：摘除+清交互态+通知 controller。幂等（世代自增后重复调用无效）。 */
    private fun teardownIfCurrent(gen: Int) {
        if (gen == -1 || gen != attachGen) return
        attachGen++
        pendingExitGen = -1
        detachWindow()
        onDismiss?.invoke()
    }

    /** 收拢启动即断触摸：窗口仍挂载但事件全穿透到下层（返回手势/点击零拦截）。 */
    private fun setWindowNotTouchable() {
        val params = currentParams ?: return
        runCatching {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager?.updateViewLayout(wrapperView, params)
        }.onFailure { HLog.w(TAG, "setNotTouchable failed: ${it.message}") }
    }

    /** 全量销毁（弃池时）：controller 在 show 失败后调用，host 不得再复用。 */
    fun destroy() {
        attachGen++ // 在场收拢回调/兜底全部失效
        detachWindow()
        composeView?.let { cv ->
            composeView = null
            runCatching { cv.disposeComposition() }
                .onFailure { HLog.w(TAG, "disposeComposition failed: ${it.message}") }
        }
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        lifecycleOwner = null
        wrapperView = null
        windowManager = null
        currentParams = null
        built = false
        pendingInput = null
    }

    /**
     * API 102 热重载释放（由 [FanMenuController.releaseForReload] 在主线程调用；幂等）。
     *
     * 为什么必须：本对象是热重载里最"重"的资源持有者——`WindowManager` 悬浮窗、Compose
     * composition、lifecycleOwner、视图树全部绑在本代 classloader 上。**只摘窗口不够**
     * （池化复用的前提正是"composition 跨呼出存活"），必须走 [destroy] 把 composition 与
     * lifecycle 一起释放；主线程 Handler 上还挂着收拢兜底回调（`teardownIfCurrent`）与预热
     * 摘窗回调，它们是旧代闭包，也要一并撤。
     * 释放后本实例**不得再用**（池化语义作废——controller 会清空 host/idleHost，
     * 新代下次呼出重新构造）。
     */
    fun releaseForReload() {
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
            .onFailure { HLog.w(TAG, "handler cleanup failed: ${it.message}") }
        runCatching { destroy() }
            .onFailure { HLog.w(TAG, "release destroy failed: ${it.message}") }
    }

    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return wrapperView?.dispatchTouchEvent(event) ?: false
    }

    private fun resolveSelection(
        x: Float, y: Float,
        dx: Float, dy: Float,
        dist: Float,
        deadZonePx: Float,
        geometry: FanGeometry
    ): Pair<Int, Int> {
        if (dist < deadZonePx) {
            lastSelectedFanIndex = -1
            lastSelectedQuickIndex = -1
            return -1 to -1
        }

        val quickCandidate = calcQuickAppCandidate(x, y, geometry)
        lastSelectedFanIndex = resolveFanSelection(dx, dy, dist, deadZonePx, geometry)
        lastSelectedQuickIndex = quickCandidate

        return resolveDualSelection(x, y, geometry)
    }

    private fun resolveDualSelection(
        x: Float, y: Float,
        geometry: FanGeometry
    ): Pair<Int, Int> {
        val fanIdx = lastSelectedFanIndex
        val quickIdx = lastSelectedQuickIndex

        return when {
            fanIdx == -1 && quickIdx == -1 -> -1 to -1
            fanIdx == -1 -> -1 to quickIdx
            quickIdx == -1 -> fanIdx to -1
            else -> {
                val touchPos = Offset(x, y)
                val fanItem = geometry.items[fanIdx]
                val fanDist = distance(touchPos, Offset(fanItem.centerX, fanItem.centerY))
                val quickCenter = computeQuickAppCenter(quickIdx, geometry)
                val quickDist = distance(touchPos, quickCenter)
                if (fanDist <= quickDist) fanIdx to -1 else -1 to quickIdx
            }
        }
    }

    private fun computeQuickAppCenter(
        index: Int,
        geometry: FanGeometry
    ): Offset {
        val density = context.resources.displayMetrics.density
        val quickIconPx = geometry.quickIconSize * density
        val pxSpacing = quickIconPx * 0.35f
        val barPadding = quickIconPx * 0.5f
        val cx = geometry.quickBarX + barPadding + index * (quickIconPx + pxSpacing) + quickIconPx / 2f
        val cy = geometry.quickBarY + barPadding + quickIconPx / 2f
        return Offset(cx, cy)
    }

    // ===== 组展开（C7）：QuickExpandMachine 事件路由 + 几何换帧 =====

    /**
     * 组触摸路径（含组时接管全部 quick 交互）。返回 true=事件已消费（恒真——
     * 机器路径完整覆盖 DOWN/MOVE/UP/CANCEL 的显示与提交）。
     */
    private fun handleGroupTouch(
        event: MotionEvent,
        geometry: FanGeometry,
        x: Float, y: Float,
        dx: Float, dy: Float, dist: Float,
        deadZonePx: Float
    ): Boolean {
        val uptime = SystemClock.uptimeMillis()
        val fanSel = resolveFanSelection(dx, dy, dist, deadZonePx, geometry)
        val quickIdx = calcQuickAppCandidate(x, y, geometry)
        val hit = when {
            quickIdx in geometry.quickApps.indices -> QuickExpandMachine.QuickHit.Quick(
                "q$quickIdx", geometry.quickApps[quickIdx].group != null
            )
            fanSel in geometry.items.indices -> QuickExpandMachine.QuickHit.FanItem(fanSel)
            else -> QuickExpandMachine.QuickHit.None
        }
        // 展开包络（仅展开态参与判定）：新栏包围盒外扩 ~0.5 图标（胶囊比例 0.5/0.25 内衬）
        val insideEnvelope = if (!expandState.expanded) true else run {
            val q = geometry.quickIconSize * context.resources.displayMetrics.density
            val n = geometry.quickApps.size
            val barW = n * q + (n - 1) * q * 0.35f + q
            val m = q * 0.5f
            x >= geometry.quickBarX - m && x <= geometry.quickBarX + barW + m &&
                y >= geometry.quickBarY - m && y <= geometry.quickBarY + 1.5f * q + m
        }
        val action = when (event.action) {
            MotionEvent.ACTION_DOWN -> QuickExpandMachine.ACTION_DOWN
            MotionEvent.ACTION_MOVE -> QuickExpandMachine.ACTION_MOVE
            MotionEvent.ACTION_UP -> QuickExpandMachine.ACTION_UP
            else -> QuickExpandMachine.ACTION_CANCEL
        }
        // 统计口径与既有路径持平（采集层不因组在场而缺样本）
        if (action == QuickExpandMachine.ACTION_DOWN || action == QuickExpandMachine.ACTION_MOVE) {
            StatsRecorder.onFanMove(x, y)
        }
        if (action == QuickExpandMachine.ACTION_UP) {
            StatsRecorder.onFanUp(
                when {
                    dist < deadZonePx -> CancelZone.DEAD
                    dist < innerCancelRadius(geometry) -> CancelZone.INNER
                    dist > outerCancelRadius(geometry) -> CancelZone.OUTER
                    else -> CancelZone.IN_BAND
                },
                deadZonePx
            )
        }
        val hadSelection = expandState.hasQuickSelection
        val (next, cmd) = QuickExpandMachine.onEvent(
            expandState,
            QuickExpandMachine.Event(action, hit, uptime, insideEnvelope),
            released, DWELL_MS, FINAL_MIN_MS
        )
        expandState = next
        if (!hadSelection && next.hasQuickSelection) StatsRecorder.onFanPreselected()

        when (cmd) {
            QuickExpandMachine.Command.EXPAND_GROUP -> {
                if (quickIdx in geometry.quickApps.indices) {
                    performExpand(geometry.quickApps[quickIdx], quickIdx, geometry)
                }
            }
            QuickExpandMachine.Command.COLLAPSE_GROUP -> performCollapse()
            QuickExpandMachine.Command.TAP_OUTSIDE -> {
                touchState.value = FanTouchState(x, y, 2, -1, -1)
                onOutsideTap?.invoke()
                return true
            }
            QuickExpandMachine.Command.LAUNCH_QUICK -> {
                val item = geometry.quickApps.getOrNull(quickIdx)
                touchState.value = FanTouchState(x, y, 2, -1, -1)
                if (item != null) {
                    HLog.i(TAG, "quick(group-path) launch: ${item.appName}")
                    onQuickAppSelected?.invoke(item)
                }
                return true
            }
            QuickExpandMachine.Command.LAUNCH_APP -> {
                val item = geometry.items.getOrNull(fanSel)?.app
                touchState.value = FanTouchState(x, y, 2, -1, -1)
                if (item != null) {
                    HLog.i(TAG, "fan(group-path) launch: ${item.packageName}")
                    onAppSelected?.invoke(item)
                }
                return true
            }
            QuickExpandMachine.Command.NONE -> Unit
        }
        // 显示更新：quick 索引取自机器选中 id（q<idx>）；fan 索引取滞回选中
        val displayQuick = next.selectedId?.removePrefix("q")?.toIntOrNull() ?: -1
        val phase = when (action) {
            QuickExpandMachine.ACTION_UP -> 2
            QuickExpandMachine.ACTION_CANCEL -> 3
            else -> 0
        }
        touchState.value = FanTouchState(x, y, phase, fanSel.takeIf { it in geometry.items.indices } ?: -1, displayQuick)
        return true
    }

    /** 展开：换 pendingInput 为子项列表 + 锚定几何重算 + quick 选中态重置（fan 侧保留） */
    private fun performExpand(groupApp: FanAppInfo, groupIdx: Int, oldGeometry: FanGeometry) {
        val children = groupApp.group
        val input = pendingInput ?: return
        if (children.isNullOrEmpty() || expandState.expanded) return
        rootQuickStash = input
        expandedAnchorCenterX = computeQuickAppCenter(groupIdx, oldGeometry).x
        pendingInput = GeometryInput(
            input.anchorX, input.anchorY, input.apps, children,
            input.isLandscape, input.cornerAnchor
        )
        lastSelectedQuickIndex = -1
        selectedSince = 0L
        streakSince = 0L
        computeAndPublishGeometry()
        HLog.i(TAG, "group expanded: ${groupApp.appName} children=${children.size} anchorX=${expandedAnchorCenterX?.toInt()}")
    }

    /** 收回根栏：还原 stash + 默认几何 + quick 选中态重置。不关扇形（规格） */
    private fun performCollapse() {
        val stash = rootQuickStash ?: return
        pendingInput = stash
        rootQuickStash = null
        expandedAnchorCenterX = null
        lastSelectedQuickIndex = -1
        selectedSince = 0L
        streakSince = 0L
        computeAndPublishGeometry()
        HLog.i(TAG, "group collapsed → root bar")
    }

    /** 控制器组分支入口（C7 三分支；机器路径通常已先处理，此为防御/外部触发口） */
    fun expandQuickGroup(app: FanAppInfo) {
        val geometry = geometryState.value ?: return
        if (expandState.expanded) return
        val idx = geometry.quickApps.indexOfFirst { it.group != null && it.appName == app.appName }
        if (idx < 0) return
        expandState = QuickExpandMachine.State(expanded = true, expandedGroupId = "q$idx")
        performExpand(geometry.quickApps[idx], idx, geometry)
    }

    /** 组路径的取消半径（与既有 innerCancel/outerCancel 同公式，供统计口径复用） */
    private fun innerCancelRadius(geometry: FanGeometry): Float {
        val density = context.resources.displayMetrics.density
        return (geometry.innerRadius * 0.85f - geometry.iconSize * density * 0.5f) * 0.75f
    }

    private fun outerCancelRadius(geometry: FanGeometry): Float {
        val density = context.resources.displayMetrics.density
        return (geometry.outerRadius + geometry.iconSize * density * 0.5f) * 1.25f
    }

    /**
     * 极坐标评分选中（实测轮六重做）：
     * - score = (Δangle/sectorWidth)² + (Δdist/ringGap)²，角度与半径双维度归一化，
     *   治"同角位内外圈项径向竞争翻转"的预选跳变
     * - 可选带门控：|dist − item.radius| ≤ max(ringGap×0.55, iconPx×0.7)——
     *   图标周围有限可选带，环间空隙与锚点近区不再误点亮远处图标；单圈退化为纯角度
     * - 分数迟滞：仅当最优项 score < 当前项 − 0.15 才切换；当前项出带立即切换。
     *   选择稳定后 DWELL 计时自然累积，修"难命中"
     */
    private fun resolveFanSelection(
        dx: Float, dy: Float, dist: Float,
        deadZonePx: Float, geometry: FanGeometry
    ): Int {
        if (dist < deadZonePx || geometry.items.isEmpty()) return -1

        val touchDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        // 扇区宽度按各自环计算（外圈 span/外圈数、内圈 span/内圈数）：此前用全 items 数平摊，
        // 内圈实际扇区更宽却被同一尺度归一化 → 分数系统性偏大，选中偏向外圈、内圈难命中
        val outerCnt = geometry.items.count { it.isOuter }
        val innerCnt = geometry.items.size - outerCnt
        val density = context.resources.displayMetrics.density
        val iconPx = geometry.iconSize * density

        val radii = geometry.items.map { it.radius }.distinct()
        val dualRing = radii.size >= 2
        val ringGap = if (dualRing) abs(radii[0] - radii[1]) else 0f
        val band = if (dualRing) maxOf(ringGap * 0.55f, iconPx * 0.7f) else iconPx * 1.6f

        var bestIdx = -1
        var bestScore = Float.MAX_VALUE
        var curScore = Float.MAX_VALUE
        geometry.items.forEach { item ->
            val dRad = abs(dist - item.radius)
            if (dRad > band) return@forEach
            val ringSector = if (item.isOuter) {
                if (outerCnt > 1) geometry.spanAngle / outerCnt else geometry.spanAngle
            } else {
                if (innerCnt > 1) geometry.spanAngle / innerCnt else geometry.spanAngle
            }
            val aNorm = angleDiffDeg(touchDeg, item.angle) / ringSector
            val rNorm = if (dualRing && ringGap > 0f) dRad / ringGap else 0f
            val score = aNorm * aNorm + rNorm * rNorm
            if (item.index == lastSelectedFanIndex) curScore = score
            if (score < bestScore) {
                bestScore = score
                bestIdx = item.index
            }
        }

        if (bestIdx == -1) return -1
        if (lastSelectedFanIndex !in geometry.items.indices || curScore == Float.MAX_VALUE) {
            return bestIdx
        }
        return if (bestScore < curScore - HYSTERESIS_MARGIN) bestIdx else lastSelectedFanIndex
    }

    private fun calcQuickAppCandidate(
        x: Float, y: Float,
        geometry: FanGeometry
    ): Int {
        if (geometry.quickApps.isEmpty()) return -1

        val touchPos = Offset(x, y)
        val density = context.resources.displayMetrics.density
        val quickIconPx = geometry.quickIconSize * density
        val hitRadius = quickIconPx * 0.8f

        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        geometry.quickApps.take(6).forEachIndexed { index, _ ->
            val center = computeQuickAppCenter(index, geometry)
            val dist = distance(touchPos, center)
            if (dist < hitRadius && dist < bestDist) {
                bestDist = dist
                bestIdx = index
            }
        }

        return bestIdx
    }

    private fun angleDiffDeg(a: Float, b: Float): Float {
        var d = abs(a - b)
        while (d > 180f) d -= 360f
        return abs(d)
    }

    private fun handleQuickBarTap(
        x: Float, y: Float,
        geometry: FanGeometry, config: FanConfig, density: Float
    ) {
        val quickAppsList = geometry.quickApps.take(6)
        if (quickAppsList.isEmpty()) {
            Log.d(TAG, "quickBarTap: no quick apps")
            return
        }
        // 命中半径用几何层生效的快捷图标尺寸（跟随扇形图标收缩），与 computeQuickAppCenter
        // 及渲染保持同一尺寸源；此前用 config 原始值，图标收缩后命中圈偏大错位。
        // 注意：本方法在"无预选松手"时作为兜底调用——触点不在任何图标 0.7 图标距内时
        // 静默返回（滑回取消的正常路径），不打日志避免与真点击混淆（实测轮 8 次滑回
        // 全部被此阈值正确拒绝）
        val quickIconPx = geometry.quickIconSize * density
        val pxSpacing = quickIconPx * 0.35f
        val barPadding = quickIconPx * 0.5f

        for (i in quickAppsList.indices) {
            val cx = geometry.quickBarX + barPadding + i * (quickIconPx + pxSpacing) + quickIconPx / 2f
            val cy = geometry.quickBarY + barPadding + quickIconPx / 2f
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (d <= quickIconPx * 0.7f) {
                HLog.i(TAG, "quickSelected: ${quickAppsList[i].packageName} (dist=${d.toInt()}px)")
                onQuickAppSelected?.invoke(quickAppsList[i])
                return
            }
        }
    }

    @Composable
    private fun FanMenuWithTheme(content: @Composable () -> Unit) {
        val mode = prefs.getString(PrefKeys.THEME_MODE, ThemeModes.MONET_SYSTEM) ?: ThemeModes.MONET_SYSTEM
        HyperSidebarTheme(colorMode = mode) {
            content()
        }
    }

    private fun buildFanConfig(): FanConfig {
        return FanConfig(
            iconSizeDp = readFloat(PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE),
            innerRadiusDp = readFloat(PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS),
            outerRadiusDp = readFloat(PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX),
            deadZoneDp = readFloat(PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE),
            useDualRing = true,
            minRadiusDp = 60f,
            maxAppsOuter = readInt(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER),
            maxAppsInner = readInt(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER),
            landscapeIconSizeDp = readFloat(PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE),
            landscapeMaxAppsOuter = readInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER),
            landscapeMaxAppsInner = readInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER),
            landscapeInnerRadiusDp = readFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, LayoutDefaults.LANDSCAPE_INNER_RADIUS),
            landscapeOuterRadiusDp = readFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS),
            cornerIconSizeDp = readFloat(PrefKeys.CORNER_ICON_SIZE, LayoutDefaults.CORNER_ICON_SIZE),
            cornerInnerRadiusDp = readFloat(PrefKeys.CORNER_INNER_RADIUS, LayoutDefaults.CORNER_INNER_RADIUS),
            cornerOuterRadiusDp = readFloat(PrefKeys.CORNER_OUTER_RADIUS, LayoutDefaults.CORNER_OUTER_RADIUS),
            cornerMaxAppsOuter = readInt(PrefKeys.CORNER_MAX_APPS_OUTER, LayoutDefaults.CORNER_MAX_APPS_OUTER),
            cornerMaxAppsInner = readInt(PrefKeys.CORNER_MAX_APPS_INNER, LayoutDefaults.CORNER_MAX_APPS_INNER)
        )
    }

    private fun readFloat(key: String, default: Float): Float {
        return try { prefs.getFloat(key, default) } catch (_: Exception) { default }
    }

    private fun readInt(key: String, default: Int): Int {
        return try { prefs.getInt(key, default) } catch (_: Exception) { default }
    }

    private fun readBoolean(key: String, default: Boolean): Boolean {
        return try { prefs.getBoolean(key, default) } catch (_: Exception) { default }
    }

    private fun readString(key: String, default: String): String {
        return try { prefs.getString(key, default) ?: default } catch (_: Exception) { default }
    }

    @Composable
    private fun extractFanThemeColors(): FanThemeColors {
        val scheme = MiuixTheme.colorScheme
        val mode = prefs.getString(PrefKeys.THEME_MODE, ThemeModes.MONET_SYSTEM) ?: ThemeModes.MONET_SYSTEM
        val isDark = mode == ThemeModes.DARK || mode == ThemeModes.MONET_DARK ||
            (mode == ThemeModes.SYSTEM || mode == ThemeModes.MONET_SYSTEM) &&
            context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        return FanThemeColors(
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
            surface = scheme.surface,
            surfaceContainer = scheme.surfaceContainer,
            surfaceContainerHigh = scheme.surfaceContainerHigh,
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariantSummary,
            outline = scheme.outline,
            background = scheme.background,
            isDark = isDark
        )
    }

    private class FanLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        init {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }
    }
}
