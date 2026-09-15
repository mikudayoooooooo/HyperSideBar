package com.lsp.hypersidebar.ui.fan

import android.app.Dialog
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
import com.lsp.hypersidebar.util.HLog

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
    private val DWELL_MS = 150L

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

    /** 本次呼出是否走毛玻璃包围盒窗口（show 时决定，preDraw/命中测试/渲染按此分派） */
    private var frostedWindow = false

    /** 毛玻璃模式的 Dialog 窗口壳（非毛玻璃=null，走裸 addView 路径） */
    private var dialog: android.app.Dialog? = null

    /** 本次呼出的壁纸位图（竖屏 launcher 且缓存就绪时非空→miuix 内部采样磨砂） */
    private var wallpaperBitmap: android.graphics.Bitmap? = null
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

    private class GeometryInput(
        val anchorX: Float,
        val anchorY: Float,
        val apps: List<FanAppInfo>,
        val quickApps: List<FanAppInfo>,
        val isLandscape: Boolean
    )

    fun show(
        anchorX: Float,
        anchorY: Float,
        apps: List<FanAppInfo>,
        quickApps: List<FanAppInfo>,
        isLandscape: Boolean
    ) {
        val wm = windowManager
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .also { windowManager = it }
        density = context.resources.displayMetrics.density
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
        pendingInput = GeometryInput(anchorX, anchorY, apps, quickApps, isLandscape)
        resetInteractionState()
        // 壁纸磨砂（0914 用户拍板"优先 miuix 内部采样"）：仅竖屏 launcher 宿主——
        // 背后恒为壁纸，采样内容=真实背景；:ui 横屏（游戏）垫壁纸=内容错误，不接。
        // 位图由 WallpaperSampler 在 init 空闲期预载，peek 零 binder；冷缓存退亚克力
        if (context.packageName == "com.miui.home") {
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
            // 毛玻璃模式（0914 定稿 Route B）：Dialog 承载 overlay 窗口 + setBackgroundBlurRadius
            // 背景模糊（AOSP 公开 API，按窗口背景 Drawable 轮廓裁剪=局部磨砂）。
            // FLAG_BLUR_BEHIND 路线退役——MIUI 把它实现为全屏糊（真机实锤 0914）。
            // 系统模糊被关（isCrossWindowBlurEnabled=false）→ 自动降级全屏窗口+窗内 scrim
            frostedWindow = readBoolean(PrefKeys.FAN_FROSTED_ENABLED, LayoutDefaults.FAN_FROSTED_ENABLED) &&
                (windowManager?.isCrossWindowBlurEnabled ?: false)
            if (frostedWindow) {
                attachDialog(wrapper, anchorX, anchorY, apps, quickApps, isLandscape)
            } else {
                val params = buildWindowParams()
                currentParams = params
                wm.addView(wrapper, params)
            }
            HLog.i(TAG, "addView: ${SystemClock.elapsedRealtime() - tAddMs}ms frosted=$frostedWindow")
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

    /** 逐呼出重置交互态（几何清空 → 首帧前不渲染，touch/选中态归零）。 */
    private fun resetInteractionState() {
        geometryState.value = null
        touchState.value = FanTouchState(0f, 0f, 3, -1)
        lastSelectedFanIndex = -1
        lastSelectedQuickIndex = -1
        selectedSince = 0L
        originValid = false
    }

    private fun computeAndPublishGeometry() {
        val input = pendingInput ?: return
        val wrapper = wrapperView ?: return
        val loc = IntArray(2)
        runCatching { wrapper.getLocationOnScreen(loc) }
        val g = if (frostedWindow) {
            // 毛玻璃：边距自适应需要全屏参考系（房间=到屏幕边的距离，而非到包围盒边），
            // 先按屏幕系算出，再平移进包围盒窗口本地系（offsetBy）
            val dm = context.resources.displayMetrics
            computeFanGeometry(
                Offset(input.anchorX, input.anchorY),
                IntSize(dm.widthPixels, dm.heightPixels),
                input.apps, input.quickApps, config, density, input.isLandscape
            ).offsetBy(-loc[0].toFloat(), -loc[1].toFloat(), IntSize(wrapper.width, wrapper.height))
        } else {
            computeFanGeometry(
                Offset(input.anchorX - loc[0], input.anchorY - loc[1]),
                IntSize(wrapper.width, wrapper.height),
                input.apps, input.quickApps, config, density, input.isLandscape
            )
        }
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
                            ) && !frostedWindow, // 毛玻璃模式全屏压暗由 FLAG_DIM_BEHIND 承担
                            frosted = frostedWindow,
                            wallpaper = wallpaperBitmap,
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
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastSelectedFanIndex = -1
                        lastSelectedQuickIndex = -1
                        selectedSince = 0L
                        val (fanSel, quickSel) = resolveSelection(
                            x, y, dx, dy, dist, deadZonePx, geometry
                        )
                        if (fanSel != -1 || quickSel != -1) selectedSince = SystemClock.uptimeMillis()
                        touchState.value = FanTouchState(x, y, 0, fanSel, quickSel)
                        Log.d(TAG, "touch DOWN fan=$fanSel quick=$quickSel dist=${dist.toInt()}")
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
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
                        if (anySelected && (fanSel != prevFan || quickSel != prevQuick)) {
                            selectedSince = SystemClock.uptimeMillis()
                        }

                        touchState.value = FanTouchState(x, y, 0, fanSel, quickSel)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val (fanSel, quickSel) = resolveSelection(
                            x, y, dx, dy, dist, deadZonePx, geometry
                        )
                        touchState.value = FanTouchState(x, y, 2, -1, -1)
                        // 取证 dump：本地/原始坐标 + 极坐标 + 命中项全量，选中错位一轮日志定位
                        val deg = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
                        // selectedSince==0 = 手势期间从未预选过（快速扫过即松手），dwell 按 0 计——
                        // 顺带修真 bug：此前 uptime−0 为巨大垃圾值，会让 <150ms 快速松手也通过
                        // 预选时长检查（违反 PRD"预选不足 150ms 松手不启动"）
                        val dwellMs = if (selectedSince == 0L) 0L else SystemClock.uptimeMillis() - selectedSince
                        val hitItem = geometry.items.getOrNull(fanSel)
                        HLog.i(
                            TAG,
                            "UP resolve: local=(${x.toInt()},${y.toInt()}) raw=(${rawX.toInt()},${rawY.toInt()}) " +
                                "dist=${dist.toInt()} deg=${"%.1f".format(deg)} fan=$fanSel" +
                                (hitItem?.let {
                                    " [${it.app.packageName} ang=${"%.1f".format(it.angle)} rad=${it.radius.toInt()} ctr=(${it.centerX.toInt()},${it.centerY.toInt()})]"
                                } ?: "") +
                                " quick=$quickSel dwell=$dwellMs"
                        )

                        val dwellTime = if (selectedSince == 0L) 0L else SystemClock.uptimeMillis() - selectedSince
                        val anySelected = fanSel in geometry.items.indices
                        val anyQuick = quickSel in geometry.quickApps.indices

                        when {
                            !anySelected && !anyQuick -> {
                                handleQuickBarTap(x, y, geometry, config, density)
                            }
                            anySelected && dwellTime < DWELL_MS -> {
                                Log.d(TAG, "dwell too short: ${dwellTime}ms, not launching")
                            }
                            anyQuick && dwellTime < DWELL_MS -> {
                                Log.d(TAG, "dwell too short: ${dwellTime}ms, not launching")
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

    /**
     * 毛玻璃 Dialog 承载（2026-09-14 Route B 定稿）：android.app.Dialog 作 overlay 窗口壳
     * （真 Window 对象→够得着 Window#setBackgroundBlurRadius），塞入现有 wrapper View 树
     * （内含 ComposeView，Compose 层零改动——hook 手动转发的事件直接调 View 方法，
     * 与窗口形态无关）。背景模糊=AOSP 公开 API，按窗口背景 Drawable 轮廓裁剪：
     * 圆角 ShapeDrawable 即模糊区域 mask，仅扇形+快捷栏包围盒内磨砂，板外背景不动。
     * FLAG_BLUR_BEHIND 路线退役（MIUI 实现成全屏糊，真机 0914 实锤）。
     */
    private fun attachDialog(
        wrapper: View,
        anchorX: Float,
        anchorY: Float,
        apps: List<FanAppInfo>,
        quickApps: List<FanAppInfo>,
        isLandscape: Boolean
    ) {
        val dm = context.resources.displayMetrics
        // 屏幕参考系几何（仅用于求包围盒；渲染几何在 preDraw 按实际窗口原点平移）
        val g = computeFanGeometry(
            Offset(anchorX, anchorY), IntSize(dm.widthPixels, dm.heightPixels),
            apps, quickApps, config, density, isLandscape
        )
        val bounds = frostedBounds(g, dm)
        val dialog = Dialog(context)
        // Dialog.dismiss 只摘 DecorView，不移除 content 里的子视图——复用 wrapper 前必须
        // 手动脱离旧 parent，否则第二次呼出 setContentView 必炸 "already has a parent"
        // （真机 0914 实锤，且该路径失败会计熔断）
        (wrapper.parent as? android.view.ViewGroup)?.removeView(wrapper)
        // setContentView 必须先于 setBackgroundBlurRadius：后者内部委托 DecorView，
        // 而 DecorView 懒安装（真机 NPE 实锤 0914）——先塞内容触发 installDecor
        dialog.setContentView(wrapper)
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setLayout(bounds.width(), bounds.height())
            setGravity(Gravity.TOP or Gravity.START)
            attributes.x = bounds.left
            attributes.y = bounds.top
            setFormat(PixelFormat.TRANSLUCENT)
            setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 背景模糊（AOSP 裁剪语义：区域=背景 Drawable 轮廓；150px=AOSP 上限档）。
            setBackgroundBlurRadius(
                (LayoutDefaults.FAN_FROSTED_BLUR_RADIUS_DP * density).toInt()
            )
            // 轮廓自定义=扇形饼+快捷栏胶囊并集 Path——模糊区域贴合板的实际形状，
            // 消灭矩形框感（0914 用户反馈"框太扎眼"）；自身 draw 留空（画面由 Compose 画）
            setBackgroundDrawable(
                object : android.graphics.drawable.Drawable() {
                    override fun draw(canvas: android.graphics.Canvas) {}
                    override fun getOutline(outline: android.graphics.Outline) {
                        outline.setPath(frostRegionPath(g, bounds, quickCount = minOf(6, quickApps.size)))
                    }
                    override fun setAlpha(alpha: Int) {}
                    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
                    @Deprecated("Deprecated in Java")
                    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
                }
            )
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false) // BACK/外部点击不得绕过收拢状态机
        currentParams = null
        this.dialog = dialog
        dialog.show()
        HLog.i(
            TAG,
            "frosted dialog: box=[${"%d,%d %dx%d".format(bounds.left, bounds.top, bounds.width(), bounds.height())}] " +
                "anchorRaw=(${anchorX.toInt()},${anchorY.toInt()})"
        )
    }

    /**
     * 模糊区域轮廓 Path（窗口本地系）：扇形饼（锚点圆心+外弧扇区）∪ 快捷栏胶囊。
     * 传给背景 Drawable 的 Outline——背景模糊区域即此形状（贴合板，消灭矩形框感）。
     * g 为屏幕参考系几何，[bounds] 提供平移量。
     */
    private fun frostRegionPath(
        g: FanGeometry,
        bounds: android.graphics.Rect,
        quickCount: Int
    ): android.graphics.Path {
        val ax = g.anchor.x - bounds.left
        val ay = g.anchor.y - bounds.top
        val r = g.outerRadius
        val sector = android.graphics.Path().apply {
            moveTo(ax, ay)
            arcTo(
                android.graphics.RectF(ax - r, ay - r, ax + r, ay + r),
                g.startAngle, g.spanAngle, false
            )
            close()
        }
        if (quickCount <= 0) return sector
        val q = g.quickIconSize * density
        val bx = g.quickBarX - bounds.left
        val by = g.quickBarY - bounds.top
        val w = quickCount * q + (quickCount - 1) * q * 0.35f + q
        val h = q * 1.5f
        val cr = (g.quickIconSize / 2f + 4f) * density
        val capsule = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(bx, by, bx + w, by + h), cr, cr,
                android.graphics.Path.Direction.CW
            )
        }
        return android.graphics.Path().apply { op(sector, capsule, android.graphics.Path.Op.UNION) }
    }

    /**
     * 扇形+快捷栏包围盒（屏幕系，px）：弧扫角极值 ∪ 图标（含 1.25 选中放大）∪ 快捷栏矩形，
     * 外扩=雾化 blur 羽化溢出+呼吸（顶部另加选中标签余量），最后钳回屏幕。
     * 尺寸/间距公式与 computeFanGeometry/渲染同源（quickIcon 0.35 间距、0.5 边距）。
     */
    private fun frostedBounds(g: FanGeometry, dm: android.util.DisplayMetrics): android.graphics.Rect {
        val iconHalf = g.iconSize * density * 0.5f * SELECTED_ICON_SCALE
        var l = g.anchor.x
        var t = g.anchor.y
        var r = g.anchor.x
        var b = g.anchor.y
        val (minSin, maxSin, minCos, maxCos) = sweepExtremes(g.startAngle, g.endAngle)
        // 弧界（外弧覆盖内弧：同圆心同角域）
        l = minOf(l, g.anchor.x + g.outerRadius * minCos)
        r = maxOf(r, g.anchor.x + g.outerRadius * maxCos)
        t = minOf(t, g.anchor.y + g.outerRadius * minSin)
        b = maxOf(b, g.anchor.y + g.outerRadius * maxSin)
        g.items.forEach { item ->
            l = minOf(l, item.centerX - iconHalf)
            r = maxOf(r, item.centerX + iconHalf)
            t = minOf(t, item.centerY - iconHalf)
            b = maxOf(b, item.centerY + iconHalf)
        }
        val n = minOf(6, g.quickApps.size)
        if (n > 0) {
            val q = g.quickIconSize * density
            val barW = n * q + (n - 1) * q * 0.35f + q   // 图标+间距+两侧 0.5 padding
            val barH = q * 2f
            l = minOf(l, g.quickBarX)
            r = maxOf(r, g.quickBarX + barW)
            t = minOf(t, g.quickBarY)
            b = maxOf(b, g.quickBarY + barH)
        }
        val pad = 26f * density          // 雾化 blur(6dp) 羽化溢出 + 呼吸
        val topExtra = 36f * density     // 选中标签（图标顶上方 ~10dp 间隙 + 标签高）
        l -= pad; r += pad; t -= pad + topExtra; b += pad
        val left = l.toInt().coerceIn(0, dm.widthPixels - 1)
        val top = t.toInt().coerceIn(0, dm.heightPixels - 1)
        val right = r.toInt().coerceIn(left + 1, dm.widthPixels)
        val bottom = b.toInt().coerceIn(top + 1, dm.heightPixels)
        return android.graphics.Rect(left, top, right, bottom)
    }

    /** 摘窗口并复位交互态（不动 composition/lifecycle——池化复用的前提）。 */
    private fun detachWindow() {
        // 毛玻璃路径：Dialog 壳（dismiss=摘窗；content wrapper 随之脱离，composition 保留）
        dialog?.let { d ->
            dialog = null
            runCatching { d.dismiss() }
                .onFailure { HLog.w(TAG, "dialog dismiss failed: ${it.message}") }
        }
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
        // 毛玻璃路径：Dialog window 直接改 flags（收拢启动即断触摸）
        this.dialog?.let { d ->
            runCatching {
                d.window?.let { w ->
                    w.setFlags(
                        w.attributes.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        w.attributes.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    )
                }
            }.onFailure { HLog.w(TAG, "dialog setNotTouchable failed: ${it.message}") }
            return
        }
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
            landscapeOuterRadiusDp = readFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS)
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
