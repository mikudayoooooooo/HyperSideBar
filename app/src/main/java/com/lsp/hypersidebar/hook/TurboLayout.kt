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
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
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

    override val name: String = "HookTargetBox"

    /**
     * cover 构造器计数。取代旧的 `wrapper=` 诊断口径（旧口径读 `dock.sidebar.j.Q()`，
     * OS4 上该方法已漂移为 `p.Q(long)` ⇒ 只在 OS2/OS3 有意义；构造器计数三版本等价且更直接）。
     */
    private val coverCtorCount = AtomicInteger()
    private val coverRefs = CopyOnWriteArrayList<WeakReference<View>>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastDrawableLogTime = 0L

    private val fanController: FanMenuController by lazy {
        FanMenuController(
            remotePrefs,
            launchStrategy = DirectLaunchStrategy(remotePrefs),
            onMechanismResult = { ok, reason ->
                // fan 窗口装配失败 = 机制性失败（熔断数据源）；成功 = 连续失败清零
                if (ok) breaker.recordSuccess() else breaker.recordFailure(reason)
            }
        )
    }

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
        Log.d(TAG, "hookOnTouch: hooked=$hooked")
    }

    // hookM26633Q（旧 `dock.sidebar.j.Q()` 装载与 sidebarWrapperRef）已删除：
    // 它只服务 getStats() 的 wrapper 计数，而 OS4 上该方法已漂移（j.Q() → p.Q(long)）。
    // 改用 cover 构造器计数（coverCtorCount），三版本等价且不依赖混淆名。

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
    private var sAnchorT = -1L
    private var sStallFired = false

    /** 速度窗口基准时刻（与竖屏通道同源；基准位置复用 sAnchorX/Y） */
    private var sSpeedWinT = 0L
    private var sGestureSeq = 0
    private var sFanSeen = false
    private var sPendingShow: Runnable? = null

    // 滑动确认/重置阈值（px）：滑动距离设置项换算缓存，DOWN 时刷新（与竖屏通道同键同滞回）
    private var sConfirmPx = GestureThresholds.SWIPE_CONFIRM_PX
    private var sResetPx = GestureThresholds.SWIPE_CONFIRM_PX * GestureThresholds.SWIPE_RESET_RATIO

    private fun handleStripGesture(view: View, ev: MotionEvent) {
        // fan 展示中：转发驱动；手势中途落地先合成 DOWN 起始选择状态（边缘通道同款：
        // showInternal 主线程阻塞期间丢 DOWN 会导致窗口原点/选中起点全部失效）
        if (fanController.isShowing) {
            if (!sFanSeen) {
                val down = MotionEvent.obtain(
                    ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, ev.rawX, ev.rawY, 0
                )
                try { sFanSeen = fanController.dispatchTouchEvent(down) } finally { down.recycle() }
            }
            fanController.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                fanController.dismiss()
                resetStripGesture()
            }
            return
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 配置新鲜度检查（迭代六 §11.1）：过期才后台 bind 拉取，此处仅 volatile 读
                com.lsp.hypersidebar.util.ConfigPullBridge.refreshIfStale(view.context)
                sDownX = ev.rawX
                sDownY = ev.rawY
                sGestureSeq++
                sSwipeConfirmed = false
                sStallFired = false
                sAnchorT = -1L
                sFanSeen = false
                // 内滑轴：DOWN 点就近角落的对角线（指向屏幕内部）
                val dm = view.context.resources.displayMetrics
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
                val inv = 1f / sqrt(2f)
                sInwardUx = (if (sDownX < dm.widthPixels / 2f) 1f else -1f) * inv
                sInwardUy = (if (sDownY < dm.heightPixels / 2f) 1f else -1f) * inv
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
                    vlog("s#$sGestureSeq RESET slide-back (inward=${inward.toInt()}px < ${sResetPx.toInt()})")
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
                        sAnchorT = ev.eventTime
                        sSpeedWinT = ev.eventTime
                        vlog("s#$sGestureSeq swipe confirmed: travel=${travel.toInt()}px (>= ${sConfirmPx.toInt()}) angle=${angle.toInt()}")
                    }
                }

                if (sSwipeConfirmed && !sStallFired) {
                    // 速度判据（0915 三轮实测定案，与竖屏通道同款）：只有"窗内平均速度仍高于
                    // 阈值"才重新计时。位移式判据会被缓慢持续漂移周期性触发，把 dwell 整轮重置
                    // ——本通道实测「确认→STALL」均值 661ms，且数值是 250ms 的整数倍
                    // （s#9=1270≈250×5、s#10=768≈250×3）。
                    val dt = ev.eventTime - sSpeedWinT
                    if (dt >= GestureThresholds.STALL_SPEED_WINDOW_MS) {
                        val speed = hypot(ev.rawX - sAnchorX, ev.rawY - sAnchorY) * 1000f / dt
                        sAnchorX = ev.rawX
                        sAnchorY = ev.rawY
                        sSpeedWinT = ev.eventTime
                        if (speed > GestureThresholds.STALL_MAX_SPEED_PX_S) {
                            sAnchorT = ev.eventTime
                        }
                    }
                    // 达标判定独立于速度：本帧刚重新计时时 sAnchorT==eventTime，差值 0 天然不误触发
                    if (ev.eventTime - sAnchorT >= stripDwellMs()) {
                        sStallFired = true
                        StatsRecorder.onStall()
                        vlog("s#$sGestureSeq STALL ${stripDwellMs()}ms anchor=(${sAnchorX.toInt()},${sAnchorY.toInt()})")
                        postShowStripFan(view)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vlog("s#$sGestureSeq UP stallFired=$sStallFired shown=${fanController.isShowing}")
                if (sStallFired) {
                    cancelPendingStripShow()
                    // fan 已落地而手指未预选即松手 → 立即收起（PRD"未预选松手→立即收起"）
                    if (fanController.isShowing) fanController.dismiss()
                }
                resetStripGesture()
            }
        }
    }

    /**
     * 停顿触发 → 弹 fan。圆心 X=呼出起始侧屏幕边缘（与边缘通道一致）；
     * 圆心 Y 横屏固定为条中心（条=[0,112dp] → 56dp，PRD §9.5 修订 2026-08-30：
     * 触发范围本就小，圆心不随触摸 Y，配合几何层展开角自适应向下方倾斜展开）。
     */
    private fun postShowStripFan(view: View) {
        val ctx = view.context
        val dm = ctx.resources.displayMetrics
        val anchorX = if (sDownX < dm.widthPixels / 2f) 0f else dm.widthPixels.toFloat()
        val anchorY = LANDSCAPE_ANCHOR_Y_DP * dm.density
        vlog("s#$sGestureSeq showFan: anchor=($anchorX, $anchorY) downY=${sDownY.toInt()} dwell=${stripDwellMs()}ms")
        // 耗时锚点（呼出卡顿归因）：postLag=:ui 主线程繁忙度（同 EdgeGestureHook）
        val postAtMs = android.os.SystemClock.uptimeMillis()
        val r = Runnable {
            sPendingShow = null
            vlog("s#$sGestureSeq showFan runnable: postLag=${android.os.SystemClock.uptimeMillis() - postAtMs}ms")
            fanController.show(ctx, anchorX, anchorY)
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
        sAnchorT = -1L
        sSpeedWinT = 0L
        sFanSeen = false
    }

    private fun stripDwellMs(): Long = try {
        remotePrefs.getInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS).toLong()
    } catch (_: Exception) {
        LayoutDefaults.TRIGGER_DWELL_MS.toLong()
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

    fun getStats(): String {
        return "controller=${fanController.getStats()}, cover=$coverCtorCount"
    }

    override fun init() {
        HLog.i(TAG, "=== TurboLayout init ===")
        // 结构化锚点解析（每宿主进程一次）：必须早于所有依赖锚点的 hook 安装。
        // L2 总闸可远程关闭（pref），解析失败逐 role 闭锁（不影响其它 hook、不改变既有默认路径）。
        val structuralScan = runCatching {
            remotePrefs.getBoolean(PrefKeys.ANCHOR_STRUCTURAL_SCAN, true)
        }.getOrDefault(true)
        runCatching { AnchorResolver.resolveAll(structuralScan) }
            .onFailure { HLog.w(TAG, "anchor resolve failed: ${it.message}") }
        HLog.i(TAG, "anchor: ${AnchorResolver.stateLine()}")
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

    // ===== 小白条视觉隐藏（EDGE 模式） =====

    /**
     * 小白条像素出口封口（2026-08-25 反编译取证定稿）：
     * 条的可见像素唯一来源是其 drawable 的 draw(Canvas)（运行时类 com.miui.dock.sidebar.c，
     * 画 Path 处 C7680c.java:253）。before 置空后上层无论设什么 alpha/visibility/Folme，
     * 屏幕输出恒为空白。已降级（1C）放行（条恢复可见）；类名漂移时安全降级为可见。
     */
    private fun hookHideWhiteBar() {
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
                    if (!passthroughDegraded && moduleEnabled()) it.result = null
                }
                ?.also { HLog.i(TAG, "hookHideWhiteBar: $cls.draw hooked OK") }
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
