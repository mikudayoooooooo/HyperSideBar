package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.lsp.hypersidebar.anchor.AnchorResolver
import com.lsp.hypersidebar.anchor.AnchorRoles
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.PropNames
import com.lsp.hypersidebar.ui.fan.FanMenuController
import io.github.kyuubiran.ezxhelper.core.finder.ConstructorFinder
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createBeforeHook
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.SysProps
import com.lsp.hypersidebar.util.DismissCause
import com.lsp.hypersidebar.util.StatsRecorder
import com.lsp.hypersidebar.util.toastOnMain

private const val TAG = "TurboLayout"

/** 高频明细（s#N 系列）：默认关（HLog.verboseEnabled），关时零字符串构造（§11.2） */
private fun vlog(msg: String) { if (HLog.verboseEnabled) HLog.i(TAG, msg) }

/** B 路线横屏固定圆心 Y：条中心（条=[0,112dp]，112/2=56dp；securitycenter 私有资源取常量）。 */
private const val LANDSCAPE_ANCHOR_Y_DP = 56f

/** S1 自动降级门（1C，PRD §9.4）：60s 窗口内穿透失效 ≥3 次 → 恢复原生侧边栏。 */
private const val LEAK_DEGRADE_THRESHOLD = 3

private const val DEFAULT_DEGRADE_TOAST = "扇形侧边栏：边缘穿透持续失效，已恢复原生小白条（重启后恢复扇形）"

/**
 * :ui 进程宿主（securitycenter:ui）。产品形态（PRD §7.1，唯一；channelMode 已废弃删除）：
 *
 * - B 路线横屏触发（1B，PRD §7.1/§7.3.1，反编译取证 2026-08-30）：横屏隐藏条本身即触发器
 *   （HyperCeiler 同款思路），f.onTouch 走锚点圆状态机（EdgeGestureHook 同款：内滑确认
 *   40px/60°、15px 锚点圆 + dwell 停顿、滑回重置）→ 停顿呼出 fan。触摸链路 cover.onTouch →
 *   j.d0() → bar.dispatchTouchEvent → f.onTouch 无方向分支；系统侧 setEnabled 仅在拖动动画/
 *   游戏 turbo 面板/系统隐藏侧边栏时禁用，空闲态恒 enabled。
 * - 触摸穿透（仅竖屏）：flag 施加在窗口生命周期边界——addView 添加期注入（窗口生而
 *   NOT_TOUCHABLE）+ updateViewLayout 更新期重涂（宿主任何重置即时失效）；applyCoverFlag +
 *   2s 看门狗降级为兜底。所有创建/修改 cover 窗口 lp 的路径必经 hook，竞态窗从"最多 2s"
 *   收敛为不存在。f.onTouch 吞事件分支保留作纵深防御 + 失效计数（S1 数据源）。
 * - 视觉隐藏（三层封口）：① `c.draw(Canvas)` before-skip（可见像素唯一出口，C7680c.java:253）；
 *   ② `ImageView.onDraw` 身份过滤置空；③ `View.draw` 身份过滤置空——覆盖熄屏重建/主题切换
 *   换 drawable 类等一切绘制路径。M1/N1 提示一并清理。
 * 系统侧边栏开关保持开启 → :ui 常驻 → 活动面板与 B 链路正常。
 * 总开关（设置页"启用超级侧边栏"）关闭=同降级姿态让位（原生侧边栏恢复），可回切；
 * 非 EDGE 值（HANDLE）为遗留调试通道：条可见可摸、f.onTouch 直呼 fan，产品不暴露。
 * 执行动作用 DirectLaunchStrategy（本进程直执行）。
 * 自动降级（1C，PRD §9.4）：竖屏穿透失效 ≥3 次/分钟 → 恢复原生侧边栏（条可摸/可见/
 * 事件原生流）+ toast + remotePrefs 状态标注；无自动恢复（观测通道已停用），恢复=重启
 * :ui 进程（重启手机/重启模块）。
 */
class TurboLayout(private val remotePrefs: SharedPreferences) : BaseHook() {

    // ===== 锚点来自结构化解析（adapt/anchor-resolver），不再硬编码混淆名 =====
    // 语义：未解析出来（NOT_FOUND）⇒ 返回 null ⇒ 对应 hook 跳过（逐 role 闭锁，不影响其它 hook；
    // OS2/OS3 上解析结果与旧硬编码名完全一致 ⇒ 行为零变化）。
    private val sideBar: String? get() = AnchorResolver.fqcnOf(AnchorRoles.SIDEBAR_TOUCH.role)
    private val coverView: String? get() = AnchorResolver.fqcnOf(AnchorRoles.SIDEBAR_COVER.role)
    private val whiteBarDrawable: String? get() = AnchorResolver.fqcnOf(AnchorRoles.SIDEBAR_DRAWABLE.role)
    private val handleBarView: String? get() = AnchorResolver.fqcnOf(AnchorRoles.SIDEBAR_HANDLE_BAR.role)

    /** OS4 探测常量（AIDL 名未混淆，跨版本稳定）：类存在 = OS4 的全局侧边栏宿主。
     *  注意：p7 hook 已于 2026-10-13 删除（一律放行给原生），这里只用于 isOs4Sidebar 判定。 */
    private val EVOKE_ITF = Os4Marker.SIDEBAR_ITF

    /** OS4 判据：ISidebarOverlay 存在即 OS4 系（AIDL 稳定名；OS2/OS3 无此类）。
     *  决定 hookOnTouch 走哪套条上语义（OS4=吞事件不跑状态机，见该函数注释）。 */
    @Volatile
    private var isOs4Sidebar = false

    /** re-arm 幂等守卫（OS4 L2 晚解析重跑用；OS2/3 L1 即成功，守卫不参与） */
    @Volatile private var touchHookArmed = false
    @Volatile private var coverPassThroughArmed = false
    @Volatile private var hideWhiteBarArmed = false

    override val name: String = "HookTargetBox"

    /**
     * cover 构造器计数。取代旧的 `wrapper=` 诊断口径（旧口径读 `dock.sidebar.j.Q()`，
     * OS4 上该方法已漂移为 `p.Q(long)` ⇒ 只在 OS2/OS3 有意义；构造器计数三版本等价且更直接）。
     */
    private val coverCtorCount = AtomicInteger()
    private val coverRefs = CopyOnWriteArrayList<WeakReference<View>>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastDrawableLogTime = 0L

    private val fanLazy: Lazy<FanMenuController> = lazy {
        FanMenuController(
            remotePrefs,
            launchStrategy = DirectLaunchStrategy(remotePrefs),
            onMechanismResult = { ok, reason ->
                // fan 窗口装配失败 = 机制性失败（熔断数据源）；成功 = 连续失败清零
                if (ok) breaker.recordSuccess() else breaker.recordFailure(reason)
            }
        )
    }

    /** [fanLazy] 的取值入口；释放路径要先判 `isInitialized()`，不能顺手把它初始化出来。 */
    private val fanController: FanMenuController get() = fanLazy.value

    /** 熔断器（1C 轮二）：本进程机制性失败连续 5 次 → 恢复原生侧边栏（走降级动作） */
    private val breaker = CircuitBreaker(PrefKeys.CIRCUIT_OPEN_UI, remotePrefs)

    companion object {
        /**
         * 进程内 breaker 句柄（§11.2 日志拉取）：FreeformRelayHook 的回传接收器取
         * :ui 熔断快照用——两者同进程但实例不同，hook 装配顺序不定，null=尚未装配。
         */
        @Volatile var breakerSnapshot: () -> String? = { null }
    }

    init {
        breakerSnapshot = { breaker.snapshot() }
    }

    fun hookOnTouch() {
        if (touchHookArmed) return
        val cls = sideBar ?: run {
            HLog.w(TAG, "hookOnTouch skipped: sidebar_touch 未解析（保持原生行为）")
            return
        }
        val hooked = MethodFinder.fromClass(cls)
            .filterByName("onTouch")
            .filterByParamTypes(View::class.java, MotionEvent::class.java)
            .filterByReturnType(Boolean::class.java)
            .firstOrNull()
            ?.createBeforeHook {
                val event = it.args[1] as? MotionEvent ?: return@createBeforeHook
                val view = it.args[0] as? View ?: return@createBeforeHook

                // OS4 分支（1003 晚 v2 拍板：回归模块自有判定）：竖屏+横屏条上触摸
                // 全部进 B 路线状态机（条=触发器）——系统停顿判定松手才唤出且横屏
                // 随游戏场景切模式，弃用为主触发（p7 借用降级为 switch，默认关，
                // 一律放行给原生）。不计 onCoverTouchLeak：OS4 条触摸是常态
                // 而非"穿透失效"信号，误计数会假降级。退路=总开关关/熔断/数据源
                // 死亡放行原生（条恢复可拖原生面板）。
                if (isOs4Sidebar) {
                    if (!moduleEnabled() || breaker.open || DataDeadState.dead) return@createBeforeHook
                    // 竖屏条上让位纯吞：spy 转发是竖屏唯一驱动源，条上不再喂状态机（防双喂）。
                    // 横屏不接管——条上状态机照旧（OS2/3 横屏语义，成熟路径，触发面小）。
                    // 退路不变（总开关关/熔断/数据死亡=整段放行原生）
                    // （2026-10-13 清理，用户 m03028）原「全边缘触发·实验」总开关已删除：
                    // B 路线在 OS4 恒开，竖屏条上恒为纯吞。
                    if (!isLandscape(view)) {
                        it.result = true
                        return@createBeforeHook
                    }
                    handleStripGesture(view, event)
                    it.result = true
                    return@createBeforeHook
                }

                // 竖屏=吞掉漏到小白条的事件（穿透失效信号，防唤起原生侧边栏/
                // 幽灵入口）；横屏=B 路线状态机接管（隐藏条即触发器）。
                // 已降级（1C）：竖屏事件放行原生流（不吞不计数）；
                // 数据源死亡（迭代四 §1.3）：两侧全放行原生流，不再呼出；
                // 已熔断（1C 轮二）：两侧全放行，本进程停止一切侵入；
                // 总开关关闭：两侧全放行（原生侧边栏恢复可用），不吞不计数
                if (!isLandscape(view)) {
                    if (!moduleEnabled() || passthroughDegraded || breaker.open || DataDeadState.dead) {
                        return@createBeforeHook
                    }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onCoverTouchLeak()
                    it.result = true
                    return@createBeforeHook
                }
                if (!moduleEnabled() || breaker.open || DataDeadState.dead) return@createBeforeHook
                handleStripGesture(view, event)
                it.result = true
            }
        if (hooked != null) {
            touchHookArmed = true
            // Log.d 只进 logcat（OS4 实机已死），re-arm 可见性走 HLog（1003 晚：导出里
            // 看不到 hookOnTouch 是否重挂成功）
            HLog.i(TAG, "hookOnTouch: hooked=$hooked (armed=$touchHookArmed)")
        }
    }

    // hookM26633Q（旧 `dock.sidebar.j.Q()` 装载与 sidebarWrapperRef）已删除：
    // 它只服务 getStats() 的 wrapper 计数，而 OS4 上该方法已漂移（j.Q() → p.Q(long)）。
    // 改用 cover 构造器计数（coverCtorCount），三版本等价且不依赖混淆名。

    // ===== R1-J'（OS4 全局侧边栏 binder p7）——2026-10-13 起「一律放行」 =====
    // 历史：这里曾 hook `com.miui.gamebooster.service.DockWindowManagerService$SidebarOverlayBinder`
    // 的 p7（`com.miui.sidebar.ISidebarOverlay` 的 (IIIII)V 呼出回调），做「借用系统边缘判定」
    // （EVOKE_BORROW_SYSTEM=开：条外边缘停顿改呼扇形）或「静默消费原生面板」（关）。
    // 2026-10-13 清理（用户 m03028「全边缘触发的实验项也不要了」+ 明确选择"一律放行给原生"）：
    // B 路线在 OS4 恒开、竖屏条上由 spy 驱动，带外/横屏的 p7 交还原生场景面板
    // ——这正是当年开关=开时的实测行为，**不需要任何 p7 hook** ⇒ 整段删除。
    // EVOKE_ITF 常量保留（下方 isOs4Sidebar 用它做 OS4 探测）。

    // ===== B 路线横屏状态机（1B，PRD §7.1/§7.3.1） =====
    // 移植 EdgeGestureHook 的锚点圆法 v2（生产验证），适配 :ui 条上触摸：
    // - 内滑轴=就近角落的对角线（反编译定稿：横屏条固定在短轴顶部角落——lp 恒为
    //   START/END|TOP + y=0 + 88×308px，m29488M 含 !isLandscape 使 y 取本地默认 0），
    //   60° 锥同时容纳纯竖直/纯水平内滑；轴不依赖 view 边界——f.onTouch 的 arg0 是 bar，
    //   面板关闭时未挂窗口，getLocationOnScreen 不可靠
    // - 条上事件一律消费：原生侧边栏逻辑（拖动/呼面板）不得在隐藏条上运行
    private var sDownX = 0f
    private var sDownY = 0f
    private var sInwardUx = 0f
    private var sInwardUy = 1f
    private var sSwipeConfirmed = false
    private var sAnchorX = 0f
    private var sAnchorY = 0f
    private var sStallFired = false

    /** 速度窗口基准时刻（与竖屏通道同源；基准位置复用 sAnchorX/Y） */
    private var sSpeedWinT = 0L
    private var sGestureSeq = 0
    private var sFanSeen = false
    private var sPendingShow: Runnable? = null

    // ===== 停顿定时器（13.1，与 EdgeGestureHook 对称）：判定从"事件到达时评估"改为
    // "定时器主动开火"——手指静止后无 MOVE，旧判定整段失联（设定 250ms 实测 900ms+，
    // dwell 滑条调参不可信）。:ui 条上触摸在主线程，定时器直接挂 mainHandler；
    // fire 无需合成 UP（原生侧边栏从未收到事件，无收尾必要）。
    private var sStallTimer: Runnable? = null
    private var sStallTimerSeq = -1
    /** fire 快照：postShowStripFan 已不依赖事件对象，此处仅日志/未来扩展留位 */
    private var sLastRawX = 0f
    private var sLastRawY = 0f

    // 滑动确认/重置阈值（px）：滑动距离设置项换算缓存，DOWN 时刷新（与竖屏通道同键同滞回）
    private var sConfirmPx = GestureThresholds.SWIPE_CONFIRM_PX
    private var sResetPx = GestureThresholds.SWIPE_CONFIRM_PX * GestureThresholds.SWIPE_RESET_RATIO

    private fun handleStripGesture(view: View, ev: MotionEvent) {
        handleStripGestureCore(view.context, isLandscape(view), ev)
    }

    /**
     * B2 喂流入口（sys spy 转发的带内流；RelayGestureSink 调用）：状态机不再依赖
     * View——横竖屏由转发方判定传入（spy 无 View 可读），上下文=:ui 进程任一 context。
     * 门控由调用方（sink: b2Enabled）负责；本函数只做判定与驱动。
     * （2026-10-13 清理）原「是否 spy 流」旗标随 pilfer 一并删除：不再有抢占指令。
     *
     * **2026-10-21（用户口径）：本入口必须带"系统返回手势已认领"的证据才允许呼出。**
     * spy 是全局 monitor，看得到系统根本不当作返回手势的触摸（状态栏高度以内起手、
     * IME、手势排除区…）；旧行为按自己的 dwell 定时器直接呼出 ⇒ 与"共用系统原生返回
     * 手势"的初衷相悖。现在 `requireSystem=true`：呼出前必须读到 native 在
     * `handle_back_gesture` 里发的 seq=0 握手（=系统手势链路认领了这条流）。
     * 条上 ROM 视图（`handleStripGesture`）本身就是系统手势链路的一部分，不设此门。
     */
    fun feedStripGesture(ctx: Context, landscape: Boolean, ev: MotionEvent) {
        handleStripGestureCore(ctx, landscape, ev, requireSystem = true)
    }

    /** B2 sink 侧总门控（isOs4 + 退路面）：sink 是竖屏唯一驱动源，恒开（实验开关已删）。 */
    internal fun b2Enabled(): Boolean =
        isOs4Sidebar && moduleEnabled() && !breaker.open && !DataDeadState.dead

    /** sink 回传令牌读取用（RelayGestureSink.requestPilfer 读 :ui 侧 remotePrefs）。 */
    internal val b2Prefs: SharedPreferences get() = remotePrefs

    private fun handleStripGestureCore(
        ctx: Context,
        landscape: Boolean,
        ev: MotionEvent,
        requireSystem: Boolean = false,
    ) {
        // fan 展示中：转发驱动；手势中途落地先合成 DOWN 起始选择状态（边缘通道同款：
        // showInternal 主线程阻塞期间丢 DOWN 会导致窗口原点/选中起点全部失效）。
        // 派发期间置 B2 流驱动旗标：ComposeFanHost 孤儿防护对转发流的 DOWN/UP 放行
        // （downTime < shownUptime 对 B 路径恒真，不旁路则拖选永无法提交）——窗体自身
        // 收到的原始尾巴不带旗标，照旧被吞，两条投递路径分离。
        if (fanController.isShowing) {
            com.lsp.hypersidebar.ui.fan.B2StreamDispatch.active = true
            try {
                if (!sFanSeen) {
                    val down = MotionEvent.obtain(
                        ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, ev.rawX, ev.rawY, 0
                    )
                    try { sFanSeen = fanController.dispatchTouchEvent(down) } finally { down.recycle() }
                }
                fanController.dispatchTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    // 常驻模式：松手未提交选中 → 转点选态（窗口保留）；现状=立即收起。
                    // UP 已提交选中时 LAUNCHED 收起在先、isShowing=false，两分支都自然跳过
                    if (fanController.isShowing) {
                        if (fanPersistentOnRelease()) fanController.onGestureEnded()
                        else fanController.dismiss()
                    }
                    resetStripGesture()
                }
            } finally {
                com.lsp.hypersidebar.ui.fan.B2StreamDispatch.active = false
            }
            return
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 配置新鲜度检查（迭代六 §11.1）：过期才后台 bind 拉取，此处仅 volatile 读
                com.lsp.hypersidebar.util.ConfigPullBridge.refreshIfStale(ctx)
                sDownX = ev.rawX
                sDownY = ev.rawY
                sGestureSeq++
                sSwipeConfirmed = false
                sStallFired = false
                sFanSeen = false
                sNativeStrict = nativeStrict()   /* 中间态档位：只在本手势 DOWN 读一次 */
                sAuthSeen = false
                sAuthSightDt = null
                sAuthDt = ev.downTime
                // 系统手势门（2026-10-21）：只有 spy 驱动的竖屏条需要额外证据；横屏的
                // spy 流 native 侧不判（hsb_band_geom 横屏 fail-closed，不发握手）
                sRequireSystem = requireSystem && !landscape && sysGateEnabled()
                cancelSysGatePoll()
                // 内滑轴：横屏条=就近角落的对角线（OS2/3 定稿，60° 锥容纳竖直/水平
                // 内滑）；OS4 竖屏条=右缘中带，轴改水平向内——对角线轴对水平内滑有
                // 0.707 投影衰减+锥倾斜（正是实测"横屏响应不如竖屏"的主因），竖屏
                // 条几何下不适用
                val dm = ctx.resources.displayMetrics
                // 滑动距离换算（dp→px，DOWN 一次缓存整条手势；设置项即时经 ConfigSync 生效）
                val distanceDp = try {
                    remotePrefs.getFloat(
                        PrefKeys.TRIGGER_MIN_DISTANCE, LayoutDefaults.TRIGGER_MIN_DISTANCE_DP
                    )
                } catch (_: Exception) {
                    LayoutDefaults.TRIGGER_MIN_DISTANCE_DP
                }
                sConfirmPx = distanceDp * dm.density
                sResetPx = sConfirmPx * GestureThresholds.SWIPE_RESET_RATIO
                if (isOs4Sidebar && !landscape) {
                    sInwardUx = if (sDownX < dm.widthPixels / 2f) 1f else -1f
                    sInwardUy = 0f
                } else {
                    val inv = 1f / sqrt(2f)
                    sInwardUx = (if (sDownX < dm.widthPixels / 2f) 1f else -1f) * inv
                    sInwardUy = (if (sDownY < dm.heightPixels / 2f) 1f else -1f) * inv
                }
                vlog(
                    "s#$sGestureSeq DOWN raw=(${ev.rawX.toInt()},${ev.rawY.toInt()}) " +
                        "axis=(${"%.2f".format(sInwardUx)},${"%.2f".format(sInwardUy)})"
                )
            }

            MotionEvent.ACTION_MOVE -> {
                // 停顿已触发（fan 装配中或已展示前）：消费所有事件
                if (sStallFired) return

                val dx = ev.rawX - sDownX
                val dy = ev.rawY - sDownY
                val inward = dx * sInwardUx + dy * sInwardUy
                val perp = abs(dx * sInwardUy - dy * sInwardUx)

                // 滑回条：整体重置（PRD 状态机"滑回边缘→待触发"；滞回=确认距离一半，
                // 与竖屏通道同款——修零滞回下近阈值悬停微漂整条清零）
                if (sSwipeConfirmed && inward < sResetPx) {
                    // 提升为常开日志（2026-10-14 10:43 分诊）：滑回条会把在飞的 AUTH 轮询/回退定时器
                    // 一起撤掉 ⇒ "按住很久却一行回退都没有、抬手只打一条未出扇形"的另一种成因。
                    // 一次手势最多一条，噪声可忽略（原为 vlog，被"详细日志"开关挡住 ⇒ 导出里看不见）。
                    com.lsp.hypersidebar.util.HLog.i(
                        TAG,
                        "s#$sGestureSeq RESET slide-back (inward=${inward.toInt()}px < ${sResetPx.toInt()})" +
                            " strict=$sNativeStrict hadPoll=${sAuthPoll != null} hadTimer=${sStallTimer != null}" +
                            "（本条流的手势判定清空：native 即使随后授权也不再呼出）"
                    )
                    resetStripGesture()
                    return
                }

                if (!sSwipeConfirmed) {
                    // PRD §9.5：内滑距离达确认线（可配置，默认 15dp）且与内滑轴夹角 ≤60°（atan2 点积/叉积形式）。
                    // 距离项用锥内位移幅值而非对角线投影——投影对纯水平/竖直内滑只有
                    // 0.707 倍（实际要滑 57px 才确认），是实测"横屏响应不如竖屏"的主因
                    // （竖屏轴=水平方向，40px 即确认，无此衰减）
                    val angle = Math.toDegrees(
                        Math.atan2(perp.toDouble(), inward.toDouble())
                    ).toFloat()
                    val travel = hypot(dx, dy)
                    if (travel >= sConfirmPx && inward > 0f &&
                        angle <= GestureThresholds.MAX_SWIPE_ANGLE_DEG
                    ) {
                        sSwipeConfirmed = true
                        sAnchorX = ev.rawX
                        sAnchorY = ev.rawY
                        sSpeedWinT = ev.eventTime
                        vlog("s#$sGestureSeq swipe confirmed: travel=${travel.toInt()}px (>= ${sConfirmPx.toInt()}) angle=${angle.toInt()}")
                        // 13.1：确认即挂停顿定时器（取代旧 sAnchorT 基准+事件内评估）。
                        // 中间态（strict=1）：同一只定时器**同时**充当回退支（多等一个宽限），
                        // 另起 25ms 轮询抢 native 那一枪 ⇒ "停止移动"只有一套语义（速度窗同源）。
                        armStallTimer(ctx, landscape)
                        if (sNativeStrict) startAuthPoll(ctx, landscape, ev.downTime)
                    }
                }

                if (sSwipeConfirmed && !sStallFired) {
                    // fire 快照（对称竖屏通道；当前仅日志/扩展留位）
                    sLastRawX = ev.rawX
                    sLastRawY = ev.rawY
                    // 速度判据（0915 三轮实测定案，与竖屏通道同款）：只有"窗内平均速度仍高于
                    // 阈值"才重启定时器。位移式判据会被缓慢持续漂移周期性触发，把 dwell 整轮重置
                    // ——本通道实测「确认→STALL」均值 661ms，且数值是 250ms 的整数倍
                    // （s#9=1270≈250×5、s#10=768≈250×3）。
                    val dt = ev.eventTime - sSpeedWinT
                    if (dt >= GestureThresholds.STALL_SPEED_WINDOW_MS) {
                        val speed = hypot(ev.rawX - sAnchorX, ev.rawY - sAnchorY) * 1000f / dt
                        sAnchorX = ev.rawX
                        sAnchorY = ev.rawY
                        sSpeedWinT = ev.eventTime
                        if (speed > GestureThresholds.STALL_MAX_SPEED_PX_S) restartStallTimer(ctx, landscape)
                    }
                    // 中间态（strict=1）：MOVE 上顺手看一眼通道（轮询仍在跑，这里只为省一次 25ms）。
                    // ⚠ 这里**不再**顺手刷新"停止移动"时刻：回退截止点只由 armStallTimer 的
                    // dwell+宽限 定时器负责（速度窗与 native 同源）。旧实现按"任意 MOVE 都刷新"
                    // 算截止点（§25.10.4），指尖静止时的微抖/缓慢漂移会不断把它推后
                    // ⇒ 真机症状 = "按住好几秒扇形才出"（2026-10-14 08:49 日志 s#29~s#31）。
                    if (sNativeStrict && sAuthPoll != null) {
                        when (pollNativeAuth(ev.downTime)) {
                            AUTH_OK -> authArrive(ctx, landscape, ev.downTime)
                            AUTH_SIGHT -> noteNativeSight(ctx, landscape, ev.downTime)
                        }
                    }
                    // 达标判定在定时器 fire（fireStall）：手指静止后无 MOVE 也照常触发
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 系统手势门的等待随流结束一起收（等不到就不呼出；结束=不可能再等）
                cancelSysGatePoll()
                // act / dur 是分诊的关键两个字段（2026-10-14 10:43 日志）：
                //   CANCEL + 很短的 dur = **这条流被别的窗口抢走了**（桌面的手势窗口 pilfer ⇒
                //   系统给观察副本发 CANCEL），不是用户抬手 ⇒ "抬手太快"那个判词会骗人；
                //   UP + 长 dur = 用户真按住很久却没出扇形 ⇒ 回退支/定时器问题（本轮修的就是这个）。
                val act = if (ev.actionMasked == MotionEvent.ACTION_CANCEL) "CANCEL" else "UP"
                val dur = ev.eventTime - ev.downTime
                vlog(
                    "s#$sGestureSeq $act dur=${dur}ms stallFired=$sStallFired shown=${fanController.isShowing} " +
                        "strict=$sNativeStrict auth=$sAuthSeen"
                )
                if (sNativeStrict && !sAuthSeen && !sStallFired) {
                    // 既没等到授权、也没到回退点：dur 短 = 抬手/被抢太快（dwell 没成立，正常不出扇形）；
                    // dur 长 = 异常（回退支没落地），见上面的修复说明。
                    val late = dur >= stripDwellMs() + authGraceMs()
                    val note = if (late) "；⚠ dur 已超回退点却仍没落地 ⇒ 回退支有问题"
                    else "；dur 短 ⇒ 正常（dwell 没成立）"
                    com.lsp.hypersidebar.util.HLog.w(
                        TAG,
                        "s#$sGestureSeq AUTH 未出扇形：$act dur=${dur}ms" +
                            "（既没等到 native 授权、也没到回退点 dwell+${authGraceMs()}ms$note）" +
                            "（${authChannelHint()}）"
                    )
                }
                cancelStallTimer()
                if (sStallFired) {
                    if (fanController.isShowing) {
                        // 常驻模式：松手未提交选中 → 转点选态（窗口保留）；现状=立即收起
                        if (fanPersistentOnRelease()) fanController.onGestureEnded()
                        else {
                            cancelPendingStripShow()
                            fanController.dismiss()
                        }
                    } else if (fanPersistentOnRelease() && sPendingShow != null) {
                        // fan 装配中就松手：照常落地并直接进点选态（呼出后常驻的边界场景）
                        fanController.markReleasedOnArrival()
                    } else {
                        cancelPendingStripShow()
                    }
                }
                resetStripGesture()
            }
        }
    }

    /**
     * 停顿触发 → 弹 fan。圆心 X=呼出起始侧屏幕边缘（与边缘通道一致）；
     * 圆心 Y 横屏固定为条中心（条=[0,112dp] → 56dp，PRD §9.5 修订 2026-08-30：
     * 触发范围本就小，圆心不随触摸 Y，配合几何层展开角自适应向下方倾斜展开）；
     * OS4 竖屏（条=右缘中带、无 launcher 通道）跟随 DOWN 高度——同 OS2/3 竖屏
     * "停顿过程中的滑动漂移不影响呼出位置"定稿（轮六）。
     */
    private fun postShowStripFan(ctx: Context, landscape: Boolean) {
        val dm = ctx.resources.displayMetrics
        val anchorX = if (sDownX < dm.widthPixels / 2f) 0f else dm.widthPixels.toFloat()
        val anchorY = if (isOs4Sidebar && !landscape) {
            sDownY
        } else {
            LANDSCAPE_ANCHOR_Y_DP * dm.density
        }
        vlog("s#$sGestureSeq showFan: anchor=($anchorX, $anchorY) downY=${sDownY.toInt()} dwell=${stripDwellMs()}ms")
        // 耗时锚点（呼出卡顿归因）：postLag=:ui 主线程繁忙度（同 EdgeGestureHook）
        val postAtMs = android.os.SystemClock.uptimeMillis()
        // source 冻结（C4）：:ui uid=1000 过服务端权限门（三代实证）；本进程前台时
        // capture 过滤 securitycenter ⇒ null=不记录
        val frozenSource = ForegroundSourceProbe.capture(HostPackages.UI_HOST)
        val r = Runnable {
            sPendingShow = null
            vlog("s#$sGestureSeq showFan runnable: postLag=${android.os.SystemClock.uptimeMillis() - postAtMs}ms")
            fanController.show(ctx, anchorX, anchorY, source = frozenSource)
        }
        sPendingShow = r
        mainHandler.post(r)
    }

    private fun cancelPendingStripShow() {
        sPendingShow?.let { mainHandler.removeCallbacks(it) }
        sPendingShow = null
    }

    private fun resetStripGesture() {
        sSwipeConfirmed = false
        sStallFired = false
        sSpeedWinT = 0L
        sFanSeen = false
        sAuthSightDt = null
        sAuthDt = 0L
        sRequireSystem = false
        cancelStallTimer()
        cancelAuthPoll()
        cancelSysGatePoll()
    }

    // ===== 中间态：native 判定 → :ui 呼出授权（2026-10-13，用户 m03327）=====
    // native 通过通道发布 `<seq>:<downTime>:<x>:<y>`（native 侧 hsb_publish_evoke，每手势一次）：
    //   seq=0  = **系统返回手势链路已认领这条流**（握手）——2026-10-21 起这是**呼出的必要条件**
    //            （系统手势门，见 fireStall；不看 strict 档，也不看抑制档）；
    //   seq≥1  = native 自己的停顿判定成立（授权）——strict=1 时优先用它决定呼出时机。
    // 候选名字必须与 native 侧 kPubProps 顺序一致（native 自选 winner 并缓存）。
    //
    // strict=0（默认）：呼出时机仍由 :ui 的 dwell 决定，但**必须有握手**；没有握手=系统没把
    // 这条流当返回手势 ⇒ 不呼出（2026-10-21 用户口径，见 SYSTEM GATE）。
    // strict=1：断言 native 的授权优先，等不到时（已握手）才回退 :ui 自判。
    //
    // 回退支的判据（2026-10-14e 重写，别再改回"最后一次 MOVE"）：与 native **同一条**
    // 停顿定时器 —— 确认即挂、速度窗内超速（> STALL_MAX_SPEED_PX_S）才重启，
    // 只是 strict 档多等一个 AUTH_FALLBACK_GRACE_MS。理由：native 的 dwell 是
    // "最后一次**够快**的移动 + dwell_ms"（速度窗判据，见 native hsb_suppress_swallow），
    // 而旧实现按"最后一次**事件**"算 ⇒ 指尖静止时的微抖/缓慢漂移（真机实测 4~11px/s）
    // 会不断刷新截止点，把回退无限推后（08:49 的 s#29 拖到 DOWN 后 6.0s 才出扇形）。
    // 改用同一条速度窗判据后：回退点 = 最后一次够快的移动 + dwell + 200ms，
    // 而 native 的发布点在 +dwell 附近 ⇒ **native 永远先到**，回退只在它沉默时落地。
    private val AUTH_STRICT_PROP = PropNames.NATIVE_STRICT

    /**
     * native 未授权时的回退宽限：回退支在 `dwell + 该值` 之后落地（速度窗判据见上）。
     * 依据（真机 m03522）：左上角 y≈110/162 起手的两条停顿手势 `:ui` 判了、native
     * **一次都没进** `handle_back_gesture`（系统把状态栏高度以内的边缘流不算返回手势）。
     * ⚠ 2026-10-21 起这一类流**直接不呼出**（系统手势门），不再靠这条宽限"抢救"——
     * 本宽限现在只用于"已握手、但 native 的 dwell 授权慢于宽限"的那一段。
     * 取 200ms 是给 native 的"发布 → 25ms 轮询命中"留余量（实测延迟 1–12ms）；它同时
     * 覆盖"native 只在事件到达时才评估 dwell"这一拍（抖动事件间隔约 8ms）。
     */
    private val AUTH_FALLBACK_GRACE_MS = 200L

    /**
     * **握手之后**的宽限（seq=0 到达后启用）。
     *
     * 为什么握手后要放宽到 400ms：握手把"起点"对齐了（回退截止点改成从 native 进场起算），
     * 但两边的 **dwell 时钟仍不完全同源** —— `:ui` 用事件自带的 eventTime 走速度窗，
     * native 只能用"事件**到达**时刻"，而桌面拿到的是积压回放（时间被压缩 ⇒ 窗内平均速度偏高
     * ⇒ 它认为"还在动"的时间更久）。真机 11:09 实测：`s#6` 握手在 08.796、native 授权 09.412
     * （= sight+616ms，而不是 sight+250ms），比 :ui 的 sight+450ms 截止点晚 **15ms** ⇒ 白丢一次单源。
     * 握手已经证明 native 就在这条流上（不是覆盖差距），所以这里多等不会伤到覆盖差距那条路：
     * 覆盖差距的流根本收不到 seq=0，走的仍是 [AUTH_FALLBACK_GRACE_MS]=200ms。
     */
    private val AUTH_HANDSHAKE_GRACE_MS = 400L
    /** 本文件代码版本标记：只用来回答"现在跑的 :ui 是哪一版 Kotlin"（装机后重编时手工递增）。 */
    private val BUILD_TAG = "midstate-2026-10-14j"
    private val AUTH_PROPS = PropNames.EVOKE_PROPS
    /** [pollNativeAuth] 的三种结果（见该函数注释）。 */
    private val AUTH_NONE = 0
    /** native 发了 seq=0 握手：它已看到本条流（但停顿还没成立）。 */
    private val AUTH_SIGHT = 1
    /** native 判出停顿并授权呼出（seq≥1）。 */
    private val AUTH_OK = 2
    private var sNativeStrict = false
    private var sAuthProp: String? = null
    private var sAuthSeen = false
    private var sAuthPoll: Runnable? = null
    /** 本手势是否已收到 native 的"我看到了"握手（收到则回退截止点从此刻重算） */
    private var sAuthSightDt: Long? = null
    /** 本手势 downTime（判词里带上，便于与 HSBNative 的 `chanpublish/back SUPPRESS` 对账） */
    private var sAuthDt = 0L
    /** 取证：轮询里见过的**任意**发布（不筛 dt），回退日志用它区分"通道静默"与"覆盖差距"。 */
    private var sAuthLastAny: String? = null
    private var sStrictLogged = false

    // ===== 系统手势门（2026-10-21）=====
    /** 本流是否必须由系统返回手势链路认领（spy 驱动的竖屏条=是；DOWN 时定） */
    private var sRequireSystem = false
    /** 等系统握手的 25ms 轮询与起点（**只等握手**，不重复等 dwell：dwell 已成立） */
    private var sSysGatePoll: Runnable? = null
    private var sSysGateStartAt = 0L

    /** 等系统握手的上限：真机实测 native 的入场滞后 290–914ms（见 native 侧 seq=0 注释），
     *  这里只兜住"系统压根没认领"的那一类（超时=不呼出，不是延迟呼出）。 */
    private val SYS_GATE_WAIT_CAP_MS = 1500L

    /** 系统手势门总闸（默认开）。`setprop debug.hsb.strip.sysgate 0` = 退回 2026-10-21 前的
     *  "自判即呼出"（诊断用；会让扇形重新出现在系统不当返回手势的流上，别用作出厂档）。 */
    private fun sysGateEnabled(): Boolean =
        sysPropGet(PropNames.STRIP_SYSGATE).trim() != "0"

    /** 读系统属性（2026-10-18 收口：实现搬进 `util.SysProps`，本文件只留委托）。 */
    private fun sysPropGet(key: String): String = SysProps.get(key)

    /** strict 档：只在本手势 DOWN 时读一次（属性读取极廉价，但没必要每事件读）。 */
    private fun nativeStrict(): Boolean {
        val v = sysPropGet(AUTH_STRICT_PROP)
        val on = v.trim().startsWith("1")
        if (!sStrictLogged) {
            sStrictLogged = true
            HLog.i(TAG, "strict 档读取：$AUTH_STRICT_PROP=\"$v\" ⇒ strict=$on")
        }
        return on
    }

    /**
     * 读通道（strict=1 的唯一判据源）。命中本手势（downTime 匹配）分两种，见 [AUTH_*]：
     * payload = `<seq>:<downTime>:<x>:<y>`；**seq=0 = native 刚看到这条流**（握手，native
     * 侧 `hsb_suppress_swallow` 候选建立那一拍发），seq≥1 = 停顿成立、授权呼出。
     */
    private fun pollNativeAuth(dt: Long): Int {
        val used = sAuthProp
        val cands = if (used != null) arrayOf(used) else AUTH_PROPS
        for (k in cands) {
            val v = sysPropGet(k).trim()
            if (v.isEmpty()) continue
            val p = v.split(':')
            if (p.size < 2) continue
            // 取证：任何一条发布都记一份（不筛 dt）——回退日志靠它区分
            // "通道整段静默（native 没装/没判过任何手势）" 与 "有发布但不含本条 ⇒ 覆盖差距"。
            sAuthLastAny = "$k=$v"
            if (p[1].toLongOrNull() != dt) continue
            sAuthProp = k
            return if ((p[0].toIntOrNull() ?: -1) == 0) AUTH_SIGHT else AUTH_OK
        }
        return AUTH_NONE
    }

    /**
     * native 的"我看到了这条流"握手（seq=0）。
     *
     * 真机 10:54 的三条手势证明：native 的首个事件比 spy 的 DOWN 晚 **290~914ms**
     * （桌面手势窗口开始收流本身就晚，拿到的是积压事件），而它的确认/dwell 全以"事件到达
     * 时刻"起算 ⇒ 任何**固定**宽限都不可能既追得上这个滞后、又不把"native 真看不到"的
     * 覆盖差距拖成长延迟。握手的语义就是把这把尺子交给 native：它在 T_sight 进场，
     * 授权必然落在 T_sight + dwell 附近 ⇒ 截止点从"确认+宽限"推到"见到我 + dwell + 宽限"。
     * 每条手势只发生一次（native 每手势只写一次 seq=0）。
     */
    private fun noteNativeSight(ctx: Context, landscape: Boolean, dt: Long) {
        if (sAuthSightDt == dt) return
        sAuthSightDt = dt
        // 重新挂回退定时器（等价于"从 native 进场时刻起算 dwell+宽限"）
        armStallTimer(ctx, landscape)
        HLog.i(
            TAG,
            "s#$sGestureSeq AUTH native 已见到本条流（seq=0，dt=$dt）⇒ 回退截止点顺延到" +
                "「native 进场 + ${stripDwellMs()}ms + ${AUTH_HANDSHAKE_GRACE_MS}ms」"
        )
    }

    /** 当前生效的回退宽限：握手前 200ms（覆盖差距不许拖慢）/ 握手后 400ms（native 确实在线上）。 */
    private fun authGraceMs(): Long =
        if (sNativeStrict && sAuthSightDt != null) AUTH_HANDSHAKE_GRACE_MS else AUTH_FALLBACK_GRACE_MS

    /** 回退日志的通道判词（objective 判据，别再用"常见于屏幕顶部 inset 内起手"那种猜测）。 */    private fun authChannelHint(): String = when {
        sAuthSightDt != null -> "native 已见到本条流（seq=0）却仍未授权 ⇒ 它的确认门/停顿没成立（对照 HSBNative 的 cf/dw 计数）"
        sAuthLastAny != null -> "通道有发布但不含本条 dt（last=$sAuthLastAny）⇒ native 没把这条流当返回手势（覆盖差距）"
        else -> "通道整段静默（本进程一条 native 发布都没读到）⇒ 查 HSBNative 的 hooks ready / band geom"
    }

    /**
     * native 授权轮询（strict=1）：25ms 一次读通道；命中授权即 [authArrive]，只命中握手
     * 即 [noteNativeSight]（顺延回退截止点）。**本函数不做超时/回退**：回退由
     * [armStallTimer]（dwell + [AUTH_FALLBACK_GRACE_MS]）统一落地 ⇒ "什么时候不再等 native"
     * 只有一处判据，且与 native 的速度窗语义同源。
     */
    private fun startAuthPoll(ctx: Context, landscape: Boolean, dt: Long) {
        cancelAuthPoll()
        sAuthDt = dt
        val r = object : Runnable {
            override fun run() {
                if (sStallFired) { sAuthPoll = null; return }
                when (pollNativeAuth(dt)) {
                    AUTH_OK -> { authArrive(ctx, landscape, dt); return }
                    AUTH_SIGHT -> noteNativeSight(ctx, landscape, dt)
                }
                sAuthPoll = this
                mainHandler.postDelayed(this, 25L)
            }
        }
        sAuthPoll = r
        mainHandler.postDelayed(r, 25L)
        vlog(
            "s#$sGestureSeq AUTH strict=1：native 授权优先（未到时由 dwell+" +
                "${AUTH_FALLBACK_GRACE_MS}ms 的停顿定时器回退自判；native 一发 seq=0 握手就顺延，dt=$dt）"
        )
    }

    private fun authArrive(ctx: Context, landscape: Boolean, dt: Long) {
        cancelAuthPoll()
        if (sStallFired) return
        // native 先到 ⇒ 把回退支整条撤掉（否则 200ms 后还会再落一枪"自判呼出"）
        cancelStallTimer()
        sAuthSeen = true
        sStallFired = true
        StatsRecorder.onStall()
        vlog("s#$sGestureSeq AUTH native 授权到达（dt=$dt，prop=$sAuthProp）⇒ showFan")
        postShowStripFan(ctx, landscape)
    }

    private fun cancelAuthPoll() {
        sAuthPoll?.let { mainHandler.removeCallbacks(it) }
        sAuthPoll = null
    }

    // ===== 系统手势门（2026-10-21）：等 native 的"系统已认领这条流"握手 =====

    /**
     * dwell 已成立、但还没有系统认领的证据 ⇒ 25ms 一次读通道，最多 [SYS_GATE_WAIT_CAP_MS]。
     *
     * 语义：**只等握手**（seq=0）。dwell 是我们自己已经成立的条件，不再重复计时；
     * 握手一到就按原路呼出。超时=系统没把这条流当返回手势 ⇒ 不呼出（quiet，只记一行）。
     * 唯一例外是 strict 档：握手后仍可继续等 native 的停顿授权（由 [authArrive] 负责），
     * 这里只负责"先放行到系统已认领"这一层。
     */
    private fun armSysGatePoll(ctx: Context, landscape: Boolean) {
        if (sSysGatePoll != null) return
        sSysGateStartAt = android.os.SystemClock.uptimeMillis()
        val seq = sGestureSeq
        val dt = sAuthDt
        val r = object : Runnable {
            override fun run() {
                if (sSysGatePoll !== this) return
                if (sGestureSeq != seq || sStallFired) {
                    cancelSysGatePoll()
                    return
                }
                when (pollNativeAuth(dt)) {
                    AUTH_NONE -> {
                        if (android.os.SystemClock.uptimeMillis() - sSysGateStartAt >= SYS_GATE_WAIT_CAP_MS) {
                            cancelSysGatePoll()
                            HLog.w(
                                TAG,
                                "s#$seq 系统手势门：${SYS_GATE_WAIT_CAP_MS}ms 内未见系统返回手势认领 ⇒ 不呼出" +
                                    "（${authChannelHint()}）"
                            )
                        } else {
                            mainHandler.postDelayed(this, 25L)
                        }
                    }
                    else -> {
                        cancelSysGatePoll()
                        sAuthSightDt = dt
                        HLog.i(TAG, "s#$seq 系统手势门：系统返回手势已认领（seq=0 握手，dt=$dt）⇒ 呼出")
                        fireStall(ctx, landscape)
                    }
                }
            }
        }
        sSysGatePoll = r
        mainHandler.postDelayed(r, 25L)
        vlog("s#$seq 系统手势门：dwell 已成立，等系统返回手势认领（≤${SYS_GATE_WAIT_CAP_MS}ms，等不到就不呼出）")
    }

    private fun cancelSysGatePoll() {
        sSysGatePoll?.let { mainHandler.removeCallbacks(it) }
        sSysGatePoll = null
    }

    // ===== 停顿定时器（13.1，与 EdgeGestureHook 对称；:ui 条上触摸在主线程）=====

    private fun armStallTimer(ctx: Context, landscape: Boolean) {
        cancelStallTimer()
        sStallTimerSeq = sGestureSeq
        // 中间态（strict=1）：本定时器 = **回退支**（native 授权优先，见上面的 AUTH 段）。
        // 宽限分两档：native 已握手（它确实在这条流上）⇒ 400ms；否则 200ms（覆盖差距不许拖慢）。
        val dwell = stripDwellMs() + if (sNativeStrict) authGraceMs() else 0L
        val r = Runnable { fireStall(ctx, landscape) }
        sStallTimer = r
        mainHandler.postDelayed(r, dwell)
    }

    /** 速度窗内超速=手指仍在主动移动：dwell 全额重启（语义同旧 sAnchorT=eventTime 重置）。
     *  strict 档同样重启（重启的是"回退截止点"）——否则指尖缓慢漂移时回退点会一直停在
     *  确认那一刻，等于又一次把"停止移动"算错。 */
    private fun restartStallTimer(ctx: Context, landscape: Boolean) {
        armStallTimer(ctx, landscape)
        vlog(
            "s#$sGestureSeq STALL-TIMER restart ${stripDwellMs()}ms" +
                (if (sNativeStrict) "+${authGraceMs()}ms(回退支,握手=${sAuthSightDt != null})" else "") +
                " (speed > ${GestureThresholds.STALL_MAX_SPEED_PX_S.toInt()}px/s)"
        )
    }

    private fun cancelStallTimer() {
        sStallTimer?.let { mainHandler.removeCallbacks(it) }
        sStallTimer = null
    }

    private fun fireStall(ctx: Context, landscape: Boolean) {
        if (sStallTimer == null) return  // removeCallbacks 与在途任务竞态：已取消则静默退出
        sStallTimer = null
        if (sStallTimerSeq != sGestureSeq || !sSwipeConfirmed || sStallFired) return
        if (DataDeadState.dead || !moduleEnabled()) return
        // （2026-10-13 清理）这里原有"判定单源"开关 `debug.hsb.native.judge`：置 1 时本条链只观测，
        // 呼出与抢占交给 native，由它经 p7 哨兵把起手坐标送回。
        // native 侧的借道触发（hsb_evoke_try + 全部附锚点）已随"判死档清理"整段删除 ⇒ 置 1 会变成
        // **谁都不呼出**，故开关与分支一并删除：本条链恒为 :ui 自判（native 只负责抑制与收箭头）。
        //
        // ===== 系统手势门（2026-10-21，用户口径）=====
        // spy 驱动的竖屏条：dwell 成立还不够 —— 必须已有"系统自己的返回手势链路认领了这条流"
        // 的证据（native 在 `handle_back_gesture` 里发的 seq=0 握手）。系统没把它当返回手势
        // （状态栏高度以内起手 / IME / 手势排除区…）⇒ native 那条链根本收不到 ⇒ 不呼出。
        // 等不到就 **fail-closed**：宁可这一下不出，也不在"系统不认为是返回手势"的流上出扇形。
        // 判据只认 downTime 匹配（见 pollNativeAuth），跨手势的旧发布不会被误认。
        if (sRequireSystem && sAuthSightDt == null && !sAuthSeen) {
            when (pollNativeAuth(sAuthDt)) {
                AUTH_NONE -> {
                    armSysGatePoll(ctx, landscape)
                    return
                }
                else -> sAuthSightDt = sAuthDt   // 握手或授权 = 系统已认领
            }
        }
        sStallFired = true
        StatsRecorder.onStall()
        if (sNativeStrict) {
            // strict 档：native 的 dwell 点已过 + 宽限仍无授权。到这一步**系统手势门已经过了**
            // （上面的等待只放行"握过手"的流），所以下面只决定"再等 native 的停顿授权还是自判"。
            // 旧行为在"native 从未握手"时也自判呼出 —— 那正是本门要禁掉的分叉，已删。
            cancelAuthPoll()   // 已落地，别再多打 25ms 一轮的通道读
            com.lsp.hypersidebar.util.HLog.w(
                TAG,
                "s#$sGestureSeq AUTH 未等到 native 授权（停止移动 ${stripDwellMs()}ms+" +
                    "${authGraceMs()}ms，dt=$sAuthDt，native 已握手）⇒ 回退 :ui 自判呼出" +
                    "（${authChannelHint()}）"
            )
            postShowStripFan(ctx, landscape)
            return
        }
        vlog("s#$sGestureSeq STALL-TIMER fire ${stripDwellMs()}ms anchor=(${sAnchorX.toInt()},${sAnchorY.toInt()})")
        // （2026-10-13 清理，用户 m03028）原 B2-2 抢占指令（pilfer）已整条删除：
        // 真机实证 pilfer 一开 launcher Rust 立刻收 CANCEL、native 停顿判定链走不完，
        // 而 native 才是判定单源 ⇒ 冗余兜底去掉，流全程留在 launcher 由 native 判定，
        // :ui 只经 spy 观测 + 画扇形（即已验收通过的组合）。
        postShowStripFan(ctx, landscape)
    }

    private fun stripDwellMs(): Long = try {
        remotePrefs.getInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS).toLong()
    } catch (_: Exception) {
        LayoutDefaults.TRIGGER_DWELL_MS.toLong()
    }

    // ===== B4 底角斜滑（os4-trigger-final-plan §14，反向接管=方向即仲裁）=====
    // 消费 sink 路由来的角落三角流（zone extra 单源在 spy）。判定复用 CornerTrigger
    // （OS2/3 单源，纯 x/y 数学）：仅 Action.SHOW 有意义=锥内确认 → 抢占+角落呼出；
    // CONSUME=判定中继续旁观；ABANDON_REPLAY/PASS 不接线——从未接管，系统全程未被
    // 打扰，无需重放（反向模型对锥外竖直天然免疫，§14.1）。
    private val cornerTrigger = CornerTrigger()
    private var cornerSeq = 0
    /** 本流是否进角落判定（DOWN 时定：开关+场景避让命中=全程旁观，零模块反应） */
    private var cornerEngaged = false
    /** 角落流是否已喂过 fan 合成 DOWN（同条上通道 sFanSeen 语义） */
    private var cFanSeen = false
    /** 本流最近一个 MOVE 的 history 采样数（0 = 没批处理 ⇒ 判据拒是真的判据拒） */
    private var cLastHist = -1
    /** SystemUI 侧（2a）是否已对本流 pilfer：是 ⇒ :ui 不再发跨进程抢占指令 */
    private var cSysPilfered = false

    fun feedCornerGesture(ctx: Context, ev: MotionEvent, sysPilfered: Boolean = false) {
        // fan 展示中：转发流驱动拖选（与条上通道同款；B2 流驱动旗标放行孤儿防护）
        if (fanController.isShowing) {
            com.lsp.hypersidebar.ui.fan.B2StreamDispatch.active = true
            cSysPilfered = cSysPilfered || sysPilfered
            try {
                if (!cFanSeen) {
                    val down = MotionEvent.obtain(
                        ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, ev.rawX, ev.rawY, 0
                    )
                    try { cFanSeen = fanController.dispatchTouchEvent(down) } finally { down.recycle() }
                }
                fanController.dispatchTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                        // 安全阀：被系统抢流 ⇒ 立刻收窗（见下方 UP/CANCEL 分支的同款注释）。
                        // 这里尤其关键：扇形正在展示时来 CANCEL，若不收窗，手指剩余的触摸
                        // 会继续走拖选 ⇒ 与系统 Home 叠加成"三连"。
                        cancelPendingStripShow()
                        if (fanController.isShowing) fanController.dismiss()
                    } else if (fanController.isShowing) {
                        if (fanPersistentOnRelease()) fanController.onGestureEnded()
                        else fanController.dismiss()
                    }
                    cornerTrigger.reset()
                    cFanSeen = false
                }
            } finally {
                com.lsp.hypersidebar.ui.fan.B2StreamDispatch.active = false
            }
            return
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cornerSeq++
                cFanSeen = false
                cLastHist = -1
                cSysPilfered = sysPilfered
                cornerEngaged = cornerSwipeEnabled() && !cornerAvoided(ctx)
                if (!cornerEngaged) return
                val dm = ctx.resources.displayMetrics
                val density = dm.density
                // 确认距离=内滑下限（12dp）：OS4 是抢跑模型——系统的 Rust 侧滑检测 ~10-30ms
                // 即认领快扫流（172311 导出：失败样本全是 [ui] 收到 UP/CANCEL 时未到 SHOW，
                // 确认距离用滑动距离 30dp≈82px 时 SHOW 需要 ~100-200ms，快扫必输）。
                // 防误触由锥+内滑下限+2 帧一致性承担，行程门只留锥评估的启动线。
                // OS3（DOWN 即接管、无竞争）仍用滑动距离，不受影响。
                val cornerConfirmPx = GestureThresholds.CORNER_INWARD_MIN_DP * density
                // 角落参数：2026-10-16 起不再是设置项（滑条删除），与 OS2/3 同取
                // GestureThresholds 常量——单源同值，不再有"OS4 与 OS3 各一份"的死带。
                // 热区基准是 dp 绝对值（OS4 无 NavStubView 可反射，直接算 px）。
                val widthRatio = GestureThresholds.CORNER_WIDTH_RATIO
                val minDeg = GestureThresholds.CORNER_MIN_ANGLE_DEG
                val maxDeg = GestureThresholds.CORNER_MAX_ANGLE_DEG
                val claimed = cornerTrigger.onDown(
                    ev.rawX, ev.rawY,
                    dm.widthPixels.toFloat(), dm.heightPixels.toFloat(),
                    GestureThresholds.CORNER_BAND_DP * density,
                    dm.widthPixels * widthRatio,
                    cornerConfirmPx,
                    GestureThresholds.CORNER_INWARD_MIN_DP * density,
                    minDeg, maxDeg,
                    GestureThresholds.CORNER_ABANDON_EXTRA_DEG,
                    GestureThresholds.CORNER_CONFIRM_FRAMES_FAST
                )
                // 恒打（每手势一行，低频）：底角成功率的分子=SHOW、分母=本行——
                // 曾是 vlog 致 225324 导出 verbose 关时分母缺失、无法判读调参效果
                HLog.i(TAG, "k#$cornerSeq corner DOWN raw=(${ev.rawX.toInt()},${ev.rawY.toInt()}) claimed=$claimed " +
                    "width=${(dm.widthPixels * widthRatio).toInt()} cone=[$minDeg,$maxDeg]")
            }

            MotionEvent.ACTION_MOVE -> {
                if (!cornerEngaged) return
                // 2a：SystemUI 可能在本次流转发过程中才 pilfer（它的判定与转发同帧）
                cSysPilfered = cSysPilfered || sysPilfered
                // 批处理事件的 history 也要吃：只读当前点时，10–20ms 的角滑整个被并成
                // DOWN+UP（真机实测 20/41 次 moves=0 ⇒ NO_MOVE，触发率仅 19.5%）。
                // 展开成按时间递增的采样序列逐点喂状态机——**判据一个没改**（角窗/锥角/
                // 内滑下限原样），只是"一帧"从"一次事件投递"变成"一个输入采样"：
                // OS4 本就是 confirmFrames=FAST=1 的抢跑模型，这里只是让它第一次有了
                // 可用的采样。第一个锥内点即 SHOW。
                val hs = ev.historySize
                cLastHist = hs
                for (s in ev.toGestureSamples()) {
                    if (cornerTrigger.onMove(s.x, s.y) == CornerTrigger.Action.SHOW) {
                        HLog.i(TAG, "k#$cornerSeq corner SHOW raw=(${s.x.toInt()},${s.y.toInt()}) " +
                            "samples=${hs + 1}(hist=$hs)")
                        StatsRecorder.onStall()
                        // 2a：SystemUI 侧已同进程 pilfer 过就不再发广播指令（发了只会
                        // 打出 identity mismatch 噪声）。没打过才走原来的跨进程路径。
                        if (!cSysPilfered) RelayGestureSink.requestPilfer()
                        postShowCornerFan(ctx, ev)
                        break
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (cornerEngaged) cornerTrigger.onPointerDown() // 多指=放弃判定，永不 pilfer
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!cornerEngaged) return
                val engaged = cornerTrigger.claimed && !cornerTrigger.abandoned && !cornerTrigger.consumedToEnd
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    // ⚠ 安全阀（2026-10-14 实测：贴角时会"系统 Home + 扇形 + 启动应用"三连）：
                    // CANCEL 只可能由输入系统发出——桌面 pilfer 抢走了这条流。此时系统的
                    // Home/recents 很可能**已经在跑**，我们绝不保留扇形、也绝不让排队中的
                    // show 落地：窗口留着，手指剩余的触摸会被 overlay 当成拖选 ⇒ 点中图标
                    // ⇒ "回桌面"和"打开应用"同时发生。所以 CANCEL 一律收窗 + 撤排队，
                    // 不顾常驻档（`fanPersistentOnRelease` 只对真正的 UP 有意义）。
                    cancelPendingStripShow()
                    if (fanController.isShowing) fanController.dismiss()
                } else if (engaged) {
                    if (fanController.isShowing) {
                        if (fanPersistentOnRelease()) fanController.onGestureEnded()
                        else {
                            cancelPendingStripShow()
                            fanController.dismiss()
                        }
                    } else if (sPendingShow != null) {
                        // 快扫整包到达（1007 晚）：SHOW 已发但 fan 尚未落地时手指已抬起——
                        // 沿用"撤销排队 show"会让快扫永远白做（201757 导出：整段流压缩成
                        // DOWN+UP 一次到达）。落地转点选态：点图标直启、点空白/看门狗收。
                        fanController.markReleasedOnArrival()
                    } else {
                        // 判据（2026-10-17 用户口径）：极快抡（实测 24–40ms）本来就可能是误触 ⇒
                        // 保持"不落地"的现状，但留一行能对账的证据（与 hook 侧 early-release 同文案）。
                        val durMs = ev.eventTime - ev.downTime
                        if (durMs < 120L) {
                            HLog.i(
                                TAG,
                                "k#$cornerSeq early-release dur=${durMs}ms（视为误触：抬手早于扇形落地）"
                            )
                        }
                        cancelPendingStripShow()
                    }
                }
                // 常开（每手势一行，低频）：SHOW 是分子，DOWN 是分母，本行是**分母里没成的那批
                // 的归因**。曾是 vlog 致 verbose 关时这一环完全无日志——实机出现过
                // "claimed=true 但整轮无 SHOW"，连"有没有收到 MOVE"都判不出来。
                // diag() 必须在 reset() 之前取。
                HLog.i(
                    TAG,
                    "k#$cornerSeq corner UP engaged=$engaged shown=${fanController.isShowing} " +
                        cornerTrigger.diag() + " hist=${cLastHist}"
                )
                cornerTrigger.reset()
            }
        }
    }

    private fun cornerSwipeEnabled(): Boolean =
        runCatching {
            remotePrefs.getBoolean(PrefKeys.CORNER_SWIPE_ENABLED, LayoutDefaults.CORNER_SWIPE_ENABLED)
        }.getOrDefault(LayoutDefaults.CORNER_SWIPE_ENABLED)

    /**
     * B4 场景避让（§14.5，用户拍板：全局作用域但游戏等场景不主动开启）：
     * ① 游戏加速中=Settings.Secure "gb_boosting"==1（gamebooster 服务写、DockMonitorService
     *    等三组件消费的权威信号；反编译取证 ear/bt）；
     * ② 全局隐藏手势提示线=Settings.Global "hide_gesture_line"==1（SystemUI SettingsManager
     *    同键；实现假设随本批带上）。
     * Settings 客户端按 generation 失效自刷新，DOWN 直读即可（无需自建 observer）；
     * 任一读失败=fail-open 不避让（退化=全局生效，不比现状差）。
     */
    private fun cornerAvoided(ctx: Context): Boolean {
        val boosting = runCatching {
            android.provider.Settings.Secure.getInt(ctx.contentResolver, "gb_boosting", 0) == 1
        }.getOrDefault(false)
        if (boosting) return true
        return runCatching {
            android.provider.Settings.Global.getInt(ctx.contentResolver, "hide_gesture_line", 0) == 1
        }.getOrDefault(false)
    }

    /** 角落呼出：锚点=精确底角（cornerAnchor=true → 弧占向上象限，OS3 N1 option B 语义）。
     *  :ui 无 rootView insets 可求手势条上沿（OS3 postShowFanCorner 用 launcher rootView）——
     *  锚点 y ≥ 渲染端窗口底即可，最终位置由 FanGeometry 窗口钳制定（同 OS3 注释语义）。 */
    private fun postShowCornerFan(ctx: Context, ev: MotionEvent) {
        val dm = ctx.resources.displayMetrics
        val anchorX = if (ev.rawX < dm.widthPixels / 2f) 0f else dm.widthPixels.toFloat()
        val anchorY = dm.heightPixels.toFloat()
        HLog.i(TAG, "corner→fan: anchor=($anchorX,$anchorY) down=(${ev.rawX.toInt()},${ev.rawY.toInt()})")
        val postAtMs = android.os.SystemClock.uptimeMillis()
        val frozenSource = ForegroundSourceProbe.capture(HostPackages.UI_HOST)
        val r = Runnable {
            sPendingShow = null
            vlog("k#$cornerSeq showFan(corner) runnable: postLag=${android.os.SystemClock.uptimeMillis() - postAtMs}ms")
            runCatching { fanController.show(ctx, anchorX, anchorY, cornerAnchor = true, source = frozenSource) }
                .onFailure { HLog.w(TAG, "corner show failed: ${it.message}") }
        }
        sPendingShow = r
        mainHandler.post(r)
    }

    private fun isLandscape(view: View): Boolean =
        view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * 总开关（设置页"启用超级侧边栏"，PrefKeys.ENABLED）：关闭=本进程 hook 让位，
     * 恢复原生侧边栏全部能力（条可见/可摸/事件原生流，同 passthroughDegraded 让位面）。
     * 与降级的区别：单向不可逆 vs 各决策点现读即开即生效（SyncedPrefs 读=内存缓存命中）。
     */
    private fun moduleEnabled(): Boolean =
        runCatching { remotePrefs.getBoolean(PrefKeys.ENABLED, true) }.getOrDefault(true)

    /** 常驻扇形（设置页"呼出后常驻"，PrefKeys.FAN_PERSISTENT_ON_RELEASE）：同 EdgeGestureHook。 */
    private fun fanPersistentOnRelease(): Boolean =
        runCatching {
            remotePrefs.getBoolean(PrefKeys.FAN_PERSISTENT_ON_RELEASE, LayoutDefaults.FAN_PERSISTENT_ON_RELEASE)
        }.getOrDefault(false)

    fun getStats(): String {
        return "controller=${fanController.getStats()}, cover=$coverCtorCount"
    }

    override fun init() {
        HLog.i(TAG, "=== TurboLayout init === build=$BUILD_TAG")
        // B2-1：spy 转发流的回流终点（:ui；自挂 Application.attach 注册，init 早于 attach）
        RelayGestureSink.arm(this)
        // 结构化锚点解析（每宿主进程一次）：必须早于所有依赖锚点的 hook 安装。
        // L2 总闸可远程关闭（pref），解析失败逐 role 闭锁（不影响其它 hook、不改变既有默认路径）。
        val structuralScan = runCatching {
            remotePrefs.getBoolean(PrefKeys.ANCHOR_STRUCTURAL_SCAN, true)
        }.getOrDefault(true)
        runCatching { AnchorResolver.resolveAll(structuralScan) }
            .onFailure { HLog.w(TAG, "anchor resolve failed: ${it.message}") }
        HLog.i(TAG, "anchor: ${AnchorResolver.stateLine()}")
        // OS4 判据（AIDL 稳定名，与混淆漂移无关）：决定 hookOnTouch 条上语义分支
        isOs4Sidebar = runCatching {
            Class.forName(EVOKE_ITF, false, io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider.safeClassLoader)
        }.isSuccess
        Os4Marker.sidebarPresent = isOs4Sidebar
        // native hook 补拍（仅 spike 分支；prop debug.hsb.native.hook 关=C 侧空操作）：
        // native_init 那一拍通常早于桌面自己 loadLibrary(libapp_launcher.so)，框架 dlopen
        // 回调能否覆盖该 namespace 未证 ⇒ 桌面进程这里再给两次确定可达的机会。
        if (isOs4Sidebar) {
            runCatching { com.lsp.hypersidebar.util.NativeProbe.retry() }
                .onFailure { HLog.w(TAG, "native retry failed: ${it.message}") }
            mainHandler.postDelayed({
                runCatching { com.lsp.hypersidebar.util.NativeProbe.retry() }
                    .onFailure { HLog.w(TAG, "native retry(5s) failed: ${it.message}") }
            }, 5_000L)
        }
        // 熔断器：发布本进程新鲜状态（清掉上进程生命周期遗留的熔断键）+ 熔断动作
        breaker.forceReset()
        breaker.onTripped = { reason ->
            enterDegradedMode(
                "熔断：$reason",
                "扇形连续失败，已熔断保护：恢复原生小白条，重启手机或在设置页重试"
            )
        }
        // 状态探针注入（§2.5.4）：设置页 ping → FreeformRelayHook 接收器应答时读取本进程双态
        HookProbeState.uiProvider = {
            when {
                breaker.open -> PrefKeys.PROBE_CODE_CIRCUIT
                DataDeadState.dead -> PrefKeys.PROBE_CODE_DATA_DEAD
                passthroughDegraded -> PrefKeys.PROBE_CODE_DEGRADED
                else -> PrefKeys.PROBE_CODE_OK
            }
        }
        // 数据源死亡停摆（迭代四 §1.3）：恢复原生侧边栏（走降级动作）+ 条上不再呼出；
        // DataLoader 已 toast 过原因，降级 toast 置空防重复打扰。恢复=重启手机
        com.lsp.hypersidebar.util.DataLoader.onDataSourceDead = {
            if (DataDeadState.mark()) {
                HLog.e(TAG, "data source dead: native sidebar restored, fan disabled until reboot")
                if (fanController.isShowing) fanController.dismiss(DismissCause.PREEMPTED)
                enterDegradedMode("推荐数据源死亡（连续失败≥5 且无缓存）", "")
            }
        }
        // 各 hook 独立容错：任一失败不中断其余（历史实测：某个 ClassNotFoundException 曾中断 init，
        // 导致排在其后的 hook 从未安装）
        listOf(
            { hookOnTouch() },
            { hookCoverPassThrough() },
            { hookCoverLifecycleFlags() },
            { hookHideWhiteBar() },
            { hookHandleBarPixelKill() }
        ).forEach { step ->
            runCatching { step() }.onFailure { HLog.e(TAG, "init step failed: ${it.message}", it) }
        }
        // L2 晚解析 re-arm（OS4）：锚点要等 DexKit（L1 混淆漂移解析不出），init 时刻
        // 未解析而跳过的 hook 借回调重装（多播回调，与 SidebarOverlayProbe 的触摸流
        // 观测共存）。OS2/3 L1 已全解析无 skipped，回调不触发或重跑命中幂等守卫=空转。
        AnchorResolver.addOnAnchorsLateResolved {
            listOf(
                { hookOnTouch() },
                { hookCoverPassThrough() },
                { hookHideWhiteBar() }
            ).forEach { step ->
                runCatching { step() }.onFailure { HLog.e(TAG, "re-arm step failed: ${it.message}") }
            }
        }
        // 本类已删除两个"从未生效"的 hook（role 仍保留在锚点表里，自检可继续观察漂移）：
        //   sidebar_hint_cleanup —— 目标类在 OS2/OS3 不存在、OS4 语义已变
        //   dock_layout          —— dock 子系统没有可 setVisibility 的容器（唯一候选是"被打开的面板"本身）
        // 依据见 docs/adaptation/dock-layout-semantics-verdict.md
        HLog.i(TAG, "init done: ${getStats()}")
        // 预热推荐列表缓存（:ui 侧 B 路线横屏呼出共用 DataLoader；反射 ~1s 不进呼出关键路径）。
        // :ui 的 appContext 一般立即可用；带重试防未就绪（与边缘通道同款）。
        // onReady 顺带图标预灌+空闲预热装配（与 EdgeGestureHook 同款；本进程=:ui 侧横屏
        // fan 渲染源，横屏游戏首呼出卡顿 0913 实锤后接入试装配）
        com.lsp.hypersidebar.util.DataLoader.prewarmWithRetry(
            provider = { runCatching { EzXposed.appContext }.getOrNull() },
            onReady = { ctx ->
                com.lsp.hypersidebar.util.FanPrewarmer.preloadConfiguredFanIcons(ctx, remotePrefs)
                // 壁纸位图预载（与 EdgeGestureHook 同款——双宿主一致性 0915 用户定稿）
                com.lsp.hypersidebar.util.WallpaperSampler.ensure(ctx)
                mainHandler.postDelayed({
                    runCatching { fanController.warmupAssembly(ctx) }
                        .onFailure { HLog.w(TAG, "warmupAssembly failed: ${it.message}") }
                }, com.lsp.hypersidebar.util.FanPrewarmer.WARMUP_ASSEMBLY_DELAY_MS)
            }
        )
        // 扇形 UI 类族后台预载（同 EdgeGestureHook）：:ui 侧横屏首呼出同样受益
        com.lsp.hypersidebar.util.FanUiWarmup.warm()
    }

    /**
     * API 102 热重载释放（旧代退休前由 `XposedInit.onHotReloading` 调用；幂等）。
     *
     * 为什么必须：本 hook（:ui 进程）持有的东西全都由宿主/系统长期持有，不会随旧代消失：
     *  ① 扇形悬浮窗——`ComposeFanHost` 的 `WindowManager` 窗口 + Compose composition +
     *     lifecycle 都绑在旧代 classloader 上（只摘窗不销毁 composition 仍会钉住旧代）；
     *  ② 条上/底角状态机的三个定时器与待呼出 show——都是**旧代闭包**，尤其停顿定时器到点会
     *     去 `fanController.show(...)`：即"旧代窗口复活"，必须撤；
     *  ③ `RelayGestureSink` 的 spy 流转发接收器（不注销则旧代继续被喂流）；
     *  ④ `WallpaperSampler` 的壁纸变更接收器；
     *  ⑤ 主线程上排队的回调（native retry / 预热装配 / 照壁纸等）。
     *
     * 只释放资源，**不动 native**（:ui 无 native hook；见 `XposedInit.onHotReloaded`）。
     * 任一步失败只 `HLog.w`。
     */
    override fun releaseForReload() {
        // 定时器与排队回调先撤（避免释放过程中旧代回调又开一次窗口）
        runCatching {
            cancelPendingStripShow()
            cancelStallTimer()
            cancelAuthPoll()
            mainHandler.removeCallbacksAndMessages(null)
        }.onFailure { HLog.w(TAG, "timer cleanup failed: ${it.message}") }
        runCatching { RelayGestureSink.releaseForReload() }
            .onFailure { HLog.w(TAG, "sink release failed: ${it.message}") }
        runCatching { com.lsp.hypersidebar.util.WallpaperSampler.releaseForReload() }
            .onFailure { HLog.w(TAG, "wallpaper release failed: ${it.message}") }
        // 扇形窗口：控制器从未初始化过就不要在这里把它初始化出来（isInitialized 判定）
        runCatching {
            if (fanLazy.isInitialized()) fanLazy.value.releaseForReload()
        }.onFailure { HLog.w(TAG, "fan controller release failed: ${it.message}") }
        // 内部状态机复位（释放后同代内若再被喂流，从干净状态开始；热重载路径上新代是全新实例）
        runCatching {
            resetStripGesture()
            cornerTrigger.reset()
        }.onFailure { HLog.w(TAG, "state reset failed: ${it.message}") }
        HLog.i(TAG, "releaseForReload: fan window/timers/sink released (:ui)")
    }

    /**
     * 热重载重挂（API 102；与 [releaseForReload] 对称）。
     *
     * 本类**自己没有** attach 期注册：它释放掉的两样东西挂在别处——
     * ① `RelayGestureSink` 的流接收器（它自挂 attach 钩子，热重载不重放 ⇒ 必须显式重挂）；
     * ② `WallpaperSampler` 的壁纸变更监听（注册点在本类 init 的 `DataLoader.onReady` 里，
     *    异步且依赖数据源就绪 ⇒ 释放后不能指望它"早晚再跑一次"）。
     * 其余（本类自己的 Java hooks、`RelayGestureSink.arm` 的 owner、DataLoader 预热）由新代的
     * `init()` 自然重做 —— `installFor(UI)` 会重新调它。
     */
    override fun rearmHostSide(ctx: Context) {
        runCatching { RelayGestureSink.rearmHostSide(ctx) }
            .onFailure { HLog.w(TAG, "sink rearm failed: ${it.message}") }
        runCatching { com.lsp.hypersidebar.util.WallpaperSampler.rearmHostSide(ctx) }
            .onFailure { HLog.w(TAG, "wallpaper rearm failed: ${it.message}") }
        HLog.i(TAG, "rearmHostSide: sink stream receiver + wallpaper watch restored (:ui)")
    }

    // ===== 小白条视觉隐藏（EDGE 模式） =====

    /** B0 实验开关（读系统属性，仅实验期存在；结果落 os4-trigger-final-plan 附录） */
    private fun b0KeepVisible(): Boolean =
        SysProps.get(PropNames.B0_VISIBLE) == "1"

    /**
     * 小白条像素出口封口（2026-08-25 反编译取证定稿）：
     * 条的可见像素唯一来源是其 drawable 的 draw(Canvas)（运行时类 com.miui.dock.sidebar.c，
     * 画 Path 处 C7680c.java:253）。before 置空后上层无论设什么 alpha/visibility/Folme，
     * 屏幕输出恒为空白。已降级（1C）放行（条恢复可见）；类名漂移时安全降级为可见。
     */
    private fun hookHideWhiteBar() {
        if (hideWhiteBarArmed) return
        val cls = whiteBarDrawable ?: run {
            HLog.w(TAG, "hookHideWhiteBar skipped: sidebar_drawable 未解析（条保持可见，安全降级）")
            return
        }
        runCatching {
            MethodFinder.fromClass(cls)
                .filterByName("draw")
                .filterByParamTypes(Canvas::class.java)
                .firstOrNull()
                ?.createBeforeHook {
                    // B0 实验旁路（os4-trigger-final-plan §5）：`adb shell setprop
                    // debug.hsb.b0.visible 1` 让条临时可见可摸（判别 k 是否真实触摸
                    // 入口）；重启 :ui 生效，实验完 setprop 0 或删属性即恢复隐藏
                    if (b0KeepVisible()) return@createBeforeHook
                    if (!passthroughDegraded && moduleEnabled()) it.result = null
                }
                ?.also {
                    hideWhiteBarArmed = true
                    HLog.i(TAG, "hookHideWhiteBar: $cls.draw hooked OK")
                }
                ?: HLog.w(TAG, "hookHideWhiteBar: $cls.draw NOT FOUND（条保持可见，安全降级）")
        }.onFailure { HLog.w(TAG, "hookHideWhiteBar failed: ${it.message}（条保持可见）") }
    }

    // hookHideHints（role: sidebar_hint_cleanup）已删除：
    // 目标类 `com.miui.dock.sidebar.n` 在 OS2/OS3 **类都不存在**、OS4 语义变成 Runnable（三版本实测）
    // ⇒ 该 hook 从未装成功过，属死代码。role 仍保留在锚点表里（自检可观察漂移）。

    /**
     * 触摸穿透（EDGE，实测轮七）：SidebarCoverView（com.miui.dock.sidebar.b，extends View，
     * 日志 tag "SidebarCoverView"）是侧边栏层唯一触摸入口的窗口根 view——直接 addView 到 WM，
     * 其 layoutParams 即 WindowManager.LayoutParams（轮五退役的旧机制败因是子 view 的 lp
     * 不是）。EDGE 模式对其窗口加 FLAG_NOT_TOUCHABLE（仅竖屏，横屏 B 路线条需收事件）：
     * 原小白条区域事件穿透到下层手势桩/应用（PRD 决策 6），f.onTouch 吞事件分支降级为纵深防御。
     * 看门狗兜底通道切换、系统重置与旋转后残留收敛；非 EDGE 清除 flag 保原生可用。
     */
    private fun hookCoverPassThrough() {
        if (coverPassThroughArmed) return
        val cls = coverView ?: run {
            HLog.w(TAG, "hookCoverPassThrough skipped: sidebar_cover 未解析（穿透不生效，不阻断其它 hook）")
            return
        }
        runCatching {
            ConstructorFinder.fromClass(cls).firstOrNull()
                ?.createAfterHook {
                    val view = it.thisObject as? View ?: return@createAfterHook
                    coverCtorCount.incrementAndGet()
                    HLog.i(TAG, "cover view captured (ctor): $cls")
                    purgeCoverRefs()
                    coverRefs.add(WeakReference(view))
                    applyCoverFlag(view)
                }
                ?.also { coverPassThroughArmed = true }
                ?: HLog.w(TAG, "hookCoverPassThrough: $cls ctor NOT FOUND")
        }.onFailure { HLog.w(TAG, "hookCoverPassThrough failed: ${it.message}") }
        startCoverWatchdog()
    }

    /**
     * 穿透机制升级（迭代一 v2 §4）：flag 施加挪到窗口生命周期边界。
     * - addView 添加期注入（仅竖屏）：cover 窗口生而 NOT_TOUCHABLE（宿主从未见过无 flag 状态）；
     * - updateViewLayout 更新期重涂：宿主任何带新 lp 的重置即时失效（仅在 flag 被清掉时
     *   重涂并记日志——该日志即"宿主重置频率"的取证数据）；
     * - B 路线横屏（1B）：方向感知反置——横屏条要收事件，不加 flag 且清掉旋转前竖屏
     *   注入的残留（旋转重建 lp 走 addView、位置更新走 updateViewLayout，两条边界都在此收口）。
     * hook android.view.WindowManagerImpl（框架类，:ui 内全局身份过滤，每次仅一次类名比较）。
     */
    private fun hookCoverLifecycleFlags() {
        runCatching {
            MethodFinder.fromClass("android.view.WindowManagerImpl")
                .filterByName("addView")
                .firstOrNull()
                ?.createBeforeHook {
                    val view = it.args.getOrNull(0) as? View ?: return@createBeforeHook
                    if (view.javaClass.name != coverView) return@createBeforeHook
                    val lp = it.args.getOrNull(1) as? WindowManager.LayoutParams
                        ?: return@createBeforeHook
                    // lp 位置/尺寸随日志输出：cover=白条触摸条，pos 即白条当前实际位置
                    // （横屏 B 路线的定位数据源）。带 rotation/orientation 标签自描述——
                    // 实测竖屏 y=用户自定义位置（[871,1179] 中心 1025），横屏 y=0 固定于
                    // 短轴顶部（lp 构建器无方向分支，START/END|TOP + 32×112dp）
                    applyCoverFlagAtBoundary(view, lp, "addView")
                    val rot = runCatching { view.display?.rotation ?: -1 }.getOrDefault(-1)
                    val orient = view.resources.configuration.orientation
                    HLog.i(
                        TAG,
                        "cover addView: pos=(${lp.x},${lp.y}) size=(${lp.width}x${lp.height}) " +
                            "gravity=${lp.gravity} rot=$rot orient=$orient " +
                            "touchable=${lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0}"
                    )
                }
                ?: HLog.w(TAG, "hookCoverLifecycleFlags: WindowManagerImpl.addView NOT FOUND")
        }.onFailure { HLog.w(TAG, "hookCoverLifecycleFlags[addView] failed: ${it.message}") }
        runCatching {
            MethodFinder.fromClass("android.view.WindowManagerImpl")
                .filterByName("updateViewLayout")
                .firstOrNull()
                ?.createBeforeHook {
                    val view = it.args.getOrNull(0) as? View ?: return@createBeforeHook
                    if (view.javaClass.name != coverView) return@createBeforeHook
                    val lp = it.args.getOrNull(1) as? WindowManager.LayoutParams
                        ?: return@createBeforeHook
                    applyCoverFlagAtBoundary(view, lp, "updateViewLayout")
                }
                ?: HLog.w(TAG, "hookCoverLifecycleFlags: updateViewLayout NOT FOUND")
        }.onFailure { HLog.w(TAG, "hookCoverLifecycleFlags[updateViewLayout] failed: ${it.message}") }
    }

    /**
     * 窗口边界处的 flag 期望态收敛（addView/updateViewLayout 共用）：
     * 竖屏 EDGE=注入（穿透），横屏 EDGE=清除（B 路线收事件）；仅在偏离期望时改写并记日志。
     * 已降级（1C）：竖屏期望态翻为"可触摸"（原生侧边栏恢复），任何窗口重建不再注入。
     */
    private fun applyCoverFlagAtBoundary(view: View, lp: WindowManager.LayoutParams, via: String) {
        val wantFlag = !isLandscape(view) && !passthroughDegraded && moduleEnabled()
        val hasFlag = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        when {
            wantFlag && !hasFlag -> {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                HLog.i(TAG, "cover $via: FLAG_NOT_TOUCHABLE injected (host lp was clean)")
            }
            !wantFlag && hasFlag -> {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                HLog.i(TAG, "cover $via: FLAG_NOT_TOUCHABLE cleared (B-route landscape needs touchable strip)")
            }
        }
    }

    // 穿透失效计数（S1 数据源）：EDGE 下事件本应穿透 cover 窗口，漏到 f.onTouch 即 flag 失效。
    // 每次手势计一次（仅 DOWN），按分钟窗口滚动；达阈值自动降级（1C 接线）
    private var leakWindowStartMs = 0L
    private var leakCountInWindow = 0

    // ===== 穿透失效自动降级（1C，PRD §9.4"功能让位于可用性"） =====
    // 降级 = 恢复原生侧边栏全部能力（条可摸/可见/事件走原生流），本进程 hook 让位。
    // 无自动恢复：降级期间事件走原生流，"是否已愈合"无法观测（观测通道=leak 本身
    // 已停用），盲恢复只会反复横跳——恢复路径 = 重启 :ui 进程（重启手机/重启模块）。
    @Volatile private var passthroughDegraded = false

    private fun onCoverTouchLeak() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - leakWindowStartMs >= 60_000L) {
            leakWindowStartMs = now
            leakCountInWindow = 0
        }
        leakCountInWindow++
        HLog.w(TAG, "EDGE touch leak: DOWN reached f.onTouch (穿透失效) count=$leakCountInWindow/min")
        if (leakCountInWindow >= LEAK_DEGRADE_THRESHOLD) {
            enterDegradedMode("leak $leakCountInWindow/min >= $LEAK_DEGRADE_THRESHOLD")
        }
    }

    private fun enterDegradedMode(reason: String, toast: String = DEFAULT_DEGRADE_TOAST) {
        if (passthroughDegraded) return
        passthroughDegraded = true
        HLog.e(TAG, "PASSTHROUGH DEGRADED ($reason)：恢复原生侧边栏；恢复扇形=重启手机或重启模块")
        // 三条恢复线：条可摸（applyCoverFlag 在 degraded 态反向清 flag，立即 updateViewLayout
        // 生效）、条可见（三层封口 hook 内放行）、事件不吞（hookOnTouch 分支放行原生流）
        coverRefs.forEach { ref -> ref.get()?.let { v -> applyCoverFlag(v) } }
        // 状态标注（设置页读）：remotePrefs 跨进程写，尽力而为
        runCatching {
            remotePrefs.edit().putBoolean(PrefKeys.PASSTHROUGH_DEGRADED, true).apply()
        }.onFailure { HLog.w(TAG, "degrade status write failed: ${it.message}") }
        // toast 为空串=调用方已另行告知（数据源死亡时 DataLoader 先弹「推荐数据获取失败」）
        if (toast.isNotEmpty()) toastOnMain(safeAppContext(), toast)
    }

    private fun purgeCoverRefs() {
        coverRefs.removeAll { it.get() == null }
    }

    private fun applyCoverFlag(view: View) {
        if (view.layoutParams == null || !view.isAttachedToWindow) {
            // 构造时尚未 attach：挂一次性监听，attach 后重试
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    applyCoverFlag(v)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
            return
        }
        runCatching {
            val lp = view.layoutParams as? WindowManager.LayoutParams
                ?: return@runCatching HLog.w(TAG, "applyCoverFlag: lp=${view.layoutParams?.javaClass?.name} 非 WM.LayoutParams")
            // B 路线（1B）：横屏条要收事件——仅竖屏 EDGE 期望穿透 flag；旋转后本方法
            // （看门狗 2s 周期）负责收敛残留。已降级（1C）/总开关关闭：竖屏反向清 flag 恢复可摸
            val want = !isLandscape(view) && !passthroughDegraded && moduleEnabled()
            val has = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            if (want != has) {
                lp.flags = if (want) {
                    lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(view, lp)
                HLog.i(TAG, "cover window NOT_TOUCHABLE ${if (want) "applied" else "cleared"}")
            }
        }.onFailure { HLog.w(TAG, "applyCoverFlag failed: ${it.message}") }
    }

    private fun startCoverWatchdog() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                // 手动重试检查（2s 粒度）：设置页写 circuitResetAt > 本端熔断时刻即解除
                if (breaker.open) breaker.maybeManualReset()
                purgeCoverRefs()
                if (!coverRefs.isEmpty()) {
                    coverRefs.forEach { ref -> ref.get()?.let { applyCoverFlag(it) } }
                }
                mainHandler.postDelayed(this, 2000L)
            }
        }, 2000L)
    }

    /**
     * ImageView 渲染级封口（EDGE，实测轮六）：熄屏重建/主题切换时宿主可能给
     * RegionSamplingImageView 换 drawable 实现类，单类封口 c.draw 会漏。
     * 升维到 android.widget.ImageView.onDraw 身份过滤——不管 drawable 是谁，
     * 像素出口恒被置空；与 c.draw 封口互为纵深。:ui 进程 ImageView 数量少，
     * 类名比较开销可忽略。附带 setImageDrawable 探针记录实际 drawable 类名，
     * 用于下轮日志证实"换类"猜想。
     */
    private fun hookHandleBarPixelKill() {
        runCatching {
            MethodFinder.fromClass("android.widget.ImageView")
                .filterByName("onDraw")
                .filterByParamTypes(Canvas::class.java)
                .firstOrNull()
                ?.createBeforeHook {
                    val v = it.thisObjectOrNull ?: return@createBeforeHook
                    // 已降级（1C）/总开关关闭 放行：条恢复可见
                    if (v.javaClass.name == handleBarView && !passthroughDegraded && moduleEnabled()) {
                        it.result = null
                    }
                }
                ?.also { HLog.i(TAG, "hookHandleBarPixelKill: ImageView.onDraw hooked OK") }
                ?: HLog.w(TAG, "hookHandleBarPixelKill: ImageView.onDraw NOT FOUND（降级仅靠 c.draw）")
        }.onFailure { HLog.w(TAG, "hookHandleBarPixelKill failed: ${it.message}") }

        // 第三层封口（实测轮七）：View.draw 是渲染总入口——身份过滤后置空可覆盖
        // 前景/hardware layer 等一切绘制路径；:ui 进程视图少，类名比较开销可忽略
        runCatching {
            MethodFinder.fromClass("android.view.View")
                .filterByName("draw")
                .filterByParamTypes(Canvas::class.java)
                .firstOrNull()
                ?.createBeforeHook {
                    val v = it.thisObjectOrNull ?: return@createBeforeHook
                    // 已降级（1C）/总开关关闭 放行：条恢复可见
                    if (v.javaClass.name == handleBarView && !passthroughDegraded && moduleEnabled()) {
                        it.result = null
                    }
                }
                ?.also { HLog.i(TAG, "hookHandleBarPixelKill: View.draw (L3) hooked OK") }
                ?: HLog.w(TAG, "hookHandleBarPixelKill: View.draw NOT FOUND")
        }.onFailure { HLog.w(TAG, "hookHandleBarPixelKill L3 failed: ${it.message}") }

        runCatching {
            MethodFinder.fromClass("android.widget.ImageView")
                .filterByName("setImageDrawable")
                .firstOrNull()
                ?.createAfterHook {
                    val v = it.thisObjectOrNull ?: return@createAfterHook
                    if (v.javaClass.name != handleBarView) return@createAfterHook
                    val drawable = it.args.getOrNull(0) ?: return@createAfterHook
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastDrawableLogTime > 2000L) {
                        lastDrawableLogTime = now
                        HLog.i(TAG, "handle bar drawable set: ${drawable.javaClass.name}")
                    }
                }
                ?.also { HLog.i(TAG, "hookHandleBarPixelKill: setImageDrawable probe hooked OK") }
        }.onFailure { HLog.w(TAG, "drawable probe failed: ${it.message}") }
    }

    // hookDockLayoutVisibility（role: dock_layout）已删除，依据 docs/adaptation/dock-layout-semantics-verdict.md：
    //   · dock 子系统（com.miui.dock.* / com.miui.gamebooster.service.DockWindowManagerService）里
    //     **没有**声明 setVisibility(I) 的容器 ⇒ 原实现无有效目标（OS2/OS3/OS4 实测从未安装成功）；
    //   · 全 APK 里唯一结构候选 GameToolboxMainView 是"被打开的游戏工具箱面板"本身（含 setRootView /
    //     setOnBrightnessChange / GridLayoutManager），置 GONE 会反向伤害；
    //   · dock 显隐是窗口级的（DockWindowManagerService，166 方法），且模块已用 c.draw 置空 + 穿透完成
    //     视觉封口；横屏 B 路线还依赖该 dock 窗口存在。
    // role 仍保留在锚点表里（自检可观察漂移）。
}
