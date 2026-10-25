package com.lsp.hypersidebar.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.ACTION_FAN_LAUNCH
import com.lsp.hypersidebar.ui.fan.FanMenuController
import com.lsp.hypersidebar.util.DataLoader
import com.lsp.hypersidebar.util.DismissCause
import com.lsp.hypersidebar.util.RelayToken
import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createBeforeHook
import kotlin.math.abs
import kotlin.math.hypot
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.StatsRecorder
import com.lsp.hypersidebar.util.toastOnMain

private const val TAG = "EdgeGesture"

/** 包名合法性（热路径复用，避免每次呼出重复构造 Regex） */
private val PKG_NAME_REGEX = Regex("[A-Za-z0-9._]+")

/** 高频明细（g#N 系列）：默认关（HLog.verboseEnabled），关时零字符串构造（§11.2） */
private fun vlog(msg: String) { if (HLog.verboseEnabled) HLog.i(TAG, msg) }

/**
 * 边缘手势通道（com.miui.home / GestureStubView）——spike 验证方案的生产化。
 *
 * 设计（PRD §7.1/§7.2，全部实测背书）：
 * - 与全面屏返回手势共享同一次触摸：快速滑动松手 = 原生返回（零干扰透传）；
 *   内滑确认后位移停滞 triggerDwellMs（默认 250ms，锚点圆法 v2）= 呼出 fan 并消费后续事件
 * - 触发区（仅竖屏）：[H/3, 2H/3]，DOWN 时过滤，区外完全透传；横屏触发已整体移交
 *   B 路线（:ui 的 cover 通道直接收事件，见 TurboLayout），launcher 侧横屏区置空防双触发
 * - 滑回边缘（inward 回落至确认阈值下）→ 整体重置手势状态（PRD"滑回边缘→待触发"）
 * - fan 选中项经 BroadcastLaunchStrategy 广播到 :ui 执行（B 链路实测 2-3ms）
 * - 拦截层：GestureStubView$3.onSwipeStop 翻转首参为 false（消费路径漏事件时的兜底）
 */
class EdgeGestureHook(
    remotePrefs: SharedPreferences
) : BaseHook() {

    // 批次 2 起包装为 SyncedPrefs（XposedInit 装配）：配置同步广播缓存命中优先，
    // 设置页任何写入即时生效——remotePrefs 保持 var 以便将来替换实例
    private var remotePrefs: SharedPreferences = remotePrefs

    override val name = "EdgeGesture"

    companion object {
        const val STUB_CLASS = "com.miui.home.recents.GestureStubView"
        const val CALLBACK_CLASS = "com.miui.home.recents.GestureStubView\$3"

        /** 底部热区窗口（N1 底角斜滑）：底部 25dp 带的触摸直达此处（spike S1 实锤）。 */
        const val NAV_STUB_CLASS = "com.miui.home.recents.NavStubView"
    }

    private val breaker = CircuitBreaker(PrefKeys.CIRCUIT_OPEN_HOME, remotePrefs)

    private val fanLazy: Lazy<FanMenuController> = lazy {
        FanMenuController(
            remotePrefs,
            BroadcastLaunchStrategy(
                launchAction = ACTION_FAN_LAUNCH,
                onRelayResult = { alive, what ->
                    // :ui 执行端失联 = 机制性失败（熔断数据源）；送达 = 连续失败清零
                    if (alive) breaker.recordSuccess() else breaker.recordFailure("relay dead: $what")
                    // 数据记录（§11.3）：每次转发的确定性结果（成功率口径）
                    StatsRecorder.onLaunchResult(alive)
                },
                shouldSimulateRelayDead = {
                    // v2.0.0 发布门控（review 定案）：调试开关仅 debug 构建生效——
                    // 该值跨重装存活，release 若读存量 true=远程用户自造熔断且无法关闭
                    com.lsp.hypersidebar.BuildConfig.DEBUG &&
                        runCatching { remotePrefs.getBoolean(PrefKeys.DEBUG_RELAY_BLACKHOLE, false) }
                            .getOrDefault(false)
                },
                // 随广播附令牌（:ui 侧 FreeformRelayHook 校验）：每次发送时现读，
                // 保证令牌下发后的第一次呼出就能带上新值，而不是绑死启动快照
                relayToken = { runCatching { RelayToken.read(remotePrefs) }.getOrNull() }
            ),
            onMechanismResult = { ok, reason ->
                // show 成功不清零（实测轮二踩坑）：呼出与执行端是独立机制，两次选中之间
                // 必然夹一次成功呼出，若据此清零则连续失败永远到不了阈值。执行端恢复
                // 的清零信号只有 onRelayResult(alive)
                if (!ok) breaker.recordFailure(reason)
            }
        )
    }

    /** [fanLazy] 的取值入口；释放路径要先判 `isInitialized()`，不能顺手把它初始化出来。 */
    private val fanController: FanMenuController get() = fanLazy.value

    // ===== 单指手势状态（DOWN 重置） =====
    private var downX = 0f
    private var downY = 0f
    private var swipeConfirmed = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var stallFired = false

    /** 速度窗口基准时刻（见 GestureThresholds.STALL_SPEED_WINDOW_MS；基准位置复用 anchorX/Y） */
    private var speedWinT = 0L
    private var gestureSeq = 0   // 手势取证 id：贯穿 DOWN/确认/停顿/拦截/UP 日志（S 门数据源）
    private var gestureInZone = false  // DOWN 判定的触发区归属；区外手势整条透传

    /**
     * 底角已从**侧条窗口**（GestureStubView）认领本手势：整条事件流交给角区状态机。
     *
     * 为什么需要这个旗标：角区的旧入口是 `NavStubView`（`GestureStubHome` 窗口，实测
     * frame=[0,2272]-[1080,2340]，只有最底 68px）；而侧条窗口
     * （`GestureStubLeft/Right` frame=[·,868]-[·,2272]）覆盖其上方整段——贴角起手常常落在
     * 侧条里，旧路径 `isInTriggerZone` 判 false 就整条透传，用户感受就是"底角不好呼出"。
     * 三个 stub 窗口互不重叠（dumpsys 实测），同一触摸只进其中一个 ⇒ 与 NavStubView 入口
     * 不会双处理。认领后由本旗标独占，直到 UP/CANCEL（含 handleCornerTouch 放手的尾巴）。
     */
    private var cornerOwnsGesture = false

    // ===== 停顿定时器（13.1，对齐 OS4 gesture_pause_detector 的"逐帧判静止"思路） =====
    // 旧判定把 `eventTime - anchorT >= dwell` 放在 MOVE 事件里评估——手指一停宿主就停止
    // 上报 MOVE，条件满足也没人来评估，只能等下一次抖动或 UP 结算：设定 250ms 实测
    // 902/1787/1011ms（0915 三轮），dwell 滑条调参不可信。定时器在确认/每次速度重计时
    // 时（重新）挂上，到点主动开火，不再依赖事件到达。
    private var stallTimer: Runnable? = null
    private var stallTimerSeq = -1
    /** 定时器 Handler：必须挂触摸线程的 looper——fire 里的 cancelNativeGesture 合成 UP
     *  只允许在输入线程执行（0915 定案+外部审计）。触摸线程无 looper 的兜底形态退回
     *  主线程并跳过合成 UP（onSwipeStop 拦截层仍会翻转 shouldBack 兜底收尾）。 */
    private var stallTimerHandler: android.os.Handler? = null
    private var stallTimerOnTouchThread = false

    /** fire 快照：定时器开火时手指已静止、无实时事件可抄（合成 UP 用）；合格 MOVE 持续刷新 */
    private var lastDownTime = 0L
    private var lastEventTime = 0L
    private var lastRawX = 0f
    private var lastRawY = 0f

    // 滑动确认/重置阈值（px）：滑动距离设置项换算缓存，DOWN 时刷新。
    // 重置=确认×滞回系数（0913 取证 g#44/45：零滞回下近阈值悬停 1~2px 外漂即整条清零）
    private var confirmPx = GestureThresholds.SWIPE_CONFIRM_PX
    private var resetPx = GestureThresholds.SWIPE_CONFIRM_PX * GestureThresholds.SWIPE_RESET_RATIO

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingShow: Runnable? = null

    /** 原生触摸处理器字段（OS2 `mGesturesBackController` / OS3 `mGesturesBackTouchProcessor`），
     *  解析一次后缓存（非 Volatile：仅在手势输入线程上读写） */
    private var cachedProcessorField: java.lang.reflect.Field? = null

    /** 字段解析失败的警告只打一次（避免每次呼出刷屏） */
    private var processorMissingLogged = false
    private var fanSeenThisGesture = false
    private var probeRegistered = false

    /**
     * 本进程（launcher）注册的三个接收器 + 注册用的 ctx。
     *
     * ⚠ **必须存字段**（2026-10-16 热重载审计）：原先探针接收器只存在局部变量里、另外两个是
     * 匿名对象——热重载时**无处注销**，宿主 AMS 持有的 receiver 会把旧代 classloader 钉住；
     * 新代再注册一份就会让设置页的一次 ping 收到两个应答、一次快捷方式请求被查询两遍
     * （manifest 查询是重活，且两个应答会互相覆盖 resultExtras）。
     */
    private var probeReceiver: BroadcastReceiver? = null
    private var manifestShortcutsReceiver: BroadcastReceiver? = null
    private var shortcutLaunchReceiver: BroadcastReceiver? = null
    private var registeredCtx: android.content.Context? = null

    // ===== 底角斜滑（N1，仅竖屏，opt-in）=====
    // NavStubView.onTouchEvent 入口；CornerTrigger 纯逻辑判定，本类负责消费/呼出。
    private val cornerTrigger = CornerTrigger()
    private var cornerSeq = 0

    /** 底角事件快照：锥外早退时按原时序合成重放给原生（DOWN+MOVE，原生从未见过它们
     *  ——hook 消费发生在 onTouchEvent 之前，重放=给处理器一个干净的新手势起点）。 */
    private data class CornerEventSnapshot(
        val downTime: Long,
        val eventTime: Long,
        val action: Int,
        val x: Float,
        val y: Float,
    )

    private val cornerReplayBuffer = ArrayList<CornerEventSnapshot>()
    private var cornerReplaying = false

    override fun init() {
        HLog.i(TAG, "=== EdgeGestureHook init, pid=${android.os.Process.myPid()} ===")
        // 结构化锚点解析（每宿主进程一次）：本宿主也建表并写日志。
        // 自检报告已通过 LogCollector 拉取三进程日志尾 ⇒ 这里建表即等于"三宿主回传"，
        // 不需要新增广播通道。（home 侧本轮只建表/log，不做 hook 门控 —— 门控留给 OS4 分支。）
        runCatching { com.lsp.hypersidebar.anchor.AnchorResolver.resolveAll(remotePrefs) }
            .onFailure { HLog.w(TAG, "anchor resolve failed: ${it.message}") }
        HLog.i(TAG, "anchor: ${com.lsp.hypersidebar.anchor.AnchorResolver.stateLine()}")
        // 熔断器：发布本进程新鲜状态（清掉上进程生命周期遗留的熔断键）+ 熔断动作
        breaker.forceReset()
        breaker.onTripped = { reason ->
            toastOnMain(
                safeAppContext(), "扇形连续失败，已熔断保护：返回手势不受影响，重启手机或在设置页重试"
            )
            HLog.e(TAG, "breaker tripped: $reason — 后续边缘触摸全透传")
        }
        // 数据源死亡停摆（迭代四 §1.3，用户加强语义）：推荐获取连续失败≥5 且缓存含盘
        // 仍空=ROM 不兼容信号 → 边缘手势全透传、扇形不再展示；恢复=重启手机
        //（无自动恢复，同降级语义——盲恢复会反复横跳）
        DataLoader.onDataSourceDead = {
            if (DataDeadState.mark()) {
                HLog.e(TAG, "data source dead: edge gestures passthrough, fan disabled until reboot")
                if (fanController.isShowing) fanController.dismiss(DismissCause.PREEMPTED)
            }
        }
        // 状态探针（§2.5.4）：provider 供接收器应答 + Application.attach 后注册接收器
        HookProbeState.homeProvider = {
            when {
                DataDeadState.dead -> PrefKeys.PROBE_CODE_DATA_DEAD
                breaker.open -> PrefKeys.PROBE_CODE_CIRCUIT
                else -> PrefKeys.PROBE_CODE_OK
            }
        }
        runCatching { hookProbeReceiver() }
            .onFailure { HLog.e(TAG, "probe receiver hook FAILED: ${it.message}", it) }
        val okTouch = runCatching { hookOnTouchEvent() }
            .onFailure { HLog.e(TAG, "A FAILED hookOnTouchEvent: ${it.message}", it) }
            .getOrDefault(false)
        val okStop = runCatching { hookOnSwipeStop() }
            .onFailure { HLog.e(TAG, "C FAILED hookOnSwipeStop: ${it.message}", it) }
            .getOrDefault(false)
        // 底角斜滑（N1）：NavStubView 底部热区触摸入口
        val okCorner = runCatching { hookNavBottomGesture() }
            .onFailure { HLog.e(TAG, "D FAILED hookNavBottomGesture: ${it.message}", it) }
            .getOrDefault(false)
        // okStop=真实安装结果（含兜底扫描成功）：此前"未抛异常"就算 true，曾把拦截层
        // 静默失效伪装成 installed（1C 轮一实测教训）
        HLog.i(TAG, "hooks installed: onTouchEvent=$okTouch onSwipeStop=$okStop corner=$okCorner")
        // 预热推荐列表缓存：launcher 进程 init 时 EzXposed.appContext 可能尚未就绪
        // （实测 getAppContext 直接抛 NPE 而非返回 null，首轮 prewarm skipped 是
        // "首次呼出只有固定应用"的根因）——prewarmWithRetry 每 5s 重试直到就绪
        com.lsp.hypersidebar.util.DataLoader.prewarmWithRetry(
            provider = { runCatching { EzXposed.appContext }.getOrNull() },
            onReady = { ctx ->
                // 图标预灌（AppIconCache 统一后，本进程=launcher 侧渲染源）：固定应用+快捷栏
                // 宿主+推荐区前 24。此前 fan 渲染走 IconLoader 无任何预热点，进程冷启首呼出
                // 必然字母占位闪变
                com.lsp.hypersidebar.util.FanPrewarmer.preloadConfiguredFanIcons(ctx, remotePrefs)
                // 壁纸位图预载（壁纸磨砂：竖屏 fan 垫底+miuix 内部采样，首帧即模糊）
                com.lsp.hypersidebar.util.WallpaperSampler.ensure(ctx)
                // 空闲预热装配：首次 composition+首帧绘制（含 blur 着色器首编译）是一次性
                // 大成本，游戏场景 GPU/CPU 争用下"首呼出动画卡顿"实锤（0913）——1×1 离屏
                // 窗口试装配挪到 init 空闲期，真呼出走池化快路径
                mainHandler.postDelayed({
                    runCatching { fanController.warmupAssembly(ctx) }
                        .onFailure { HLog.w(TAG, "warmupAssembly failed: ${it.message}") }
                }, com.lsp.hypersidebar.util.FanPrewarmer.WARMUP_ASSEMBLY_DELAY_MS)
            }
        )
        // 扇形 UI 类族后台预载：把 ART 校验从首呼出主线程挪走（"有时候呼出会卡"实凶）
        com.lsp.hypersidebar.util.FanUiWarmup.warm()
    }

    /**
     * 状态探针接收器（§2.5.4）：设置页对 home 进程发有序 ping（PROBE_ACTION_HOME），
     * 应答 resultCode（熔断态）。注册时机与 FreeformRelayHook 同款——hook Application.attach
     * 之后立即注册（init 时 appContext 可能尚未就绪，prewarm 同坑）。
     */
    private fun hookProbeReceiver() {
        val hooked = MethodFinder.fromClass("android.app.Application")
            .filterByName("attach")
            .filterByParamTypes(Context::class.java)
            .firstOrNull()
            ?.createAfterHook {
                val ctx = it.args[0] as? Context ?: return@createAfterHook
                armHostSide(ctx, "Application.attach")
            }
        if (hooked == null) {
            HLog.e(TAG, "Application.attach hook failed（状态探针不可用，设置页将显示无应答）")
        }
    }

    /**
     * 热重载重挂（API 102；与 [releaseForReload] 严格对称）。
     *
     * 为什么需要：本进程（launcher）的注册体全挂在 `Application.attach` 钩子里，而热重载
     * **不重放 attach** ⇒ 释放后若不显式重挂，探针应答 / manifest 快捷方式桥 / startShortcut
     * 代发 / 配置同步 / 日志回传 / 壁纸监听会一直死到宿主重启。
     * 与 attach 路径**共用同一段实现**（[armHostSide]），没有第二份注册代码。
     *
     * 覆盖范围与 [releaseForReload] 一一对应，另有两条"非资源但同样只在 attach 期做"的装配
     * （`AnchorResolver.completeWithContext` 的宿主纠正、`StatsRecorder.init` 的 ctx 注入）
     * 也放进同一段：新代若缺它们，锚点宿主判定与数据记录会静默退化。
     */
    override fun rearmHostSide(ctx: Context) = armHostSide(ctx, "hot reload")

    /** 宿主侧装配的唯一实现：attach（冷启动）与热重载重挂都走这里，全部幂等。 */
    private fun armHostSide(ctx: Context, via: String) {
        // 注销必须用**同一个 context 实例**（LoadedApk 的 receiver 表以外层 context 为键）：
        // 本进程三个接收器都用这个 ctx 注册，所以在这里就记下，别等到 probe 那一支里记
        // （probe 注册失败时也要能注销 manifest/shortcut 那两个）。
        registeredCtx = ctx
        // 状态探针接收器（§2.5.4）：设置页对 home 进程发有序 ping（PROBE_ACTION_HOME），
        // 应答 resultCode（熔断态）。
        if (!probeRegistered) {
            probeRegistered = true
            runCatching {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        // 仅应答状态，无任何动作分支；非有序广播无 resultCode 可写
                        if (isOrderedBroadcast) resultCode = HookProbeState.homeCode()
                    }
                }
                ctx.registerReceiver(
                    receiver, IntentFilter(PrefKeys.PROBE_ACTION_HOME), Context.RECEIVER_EXPORTED
                )
                probeReceiver = receiver
                HLog.i(TAG, "probe receiver registered (via $via)")
            }.onFailure {
                HLog.e(TAG, "probe receiver registration failed: ${it.message}", it)
                probeRegistered = false
            }
        }
        runCatching {
            com.lsp.hypersidebar.anchor.AnchorResolver.completeWithContext(ctx)
            HLog.i(TAG, "anchor after arm($via): ${com.lsp.hypersidebar.anchor.AnchorResolver.stateLine()}")
        }.onFailure { HLog.w(TAG, "anchor complete failed: ${it.message}") }
        // 配置同步通道（批次 2）：收设置页全量推送，根治 hook 进程死快照
        runCatching {
            com.lsp.hypersidebar.util.ConfigSync.registerHookSide(ctx)
            HLog.i(TAG, "config sync receiver registered (via $via)")
        }.onFailure { HLog.w(TAG, "config sync register failed: ${it.message}") }
        registerManifestShortcutsBridge(ctx, via)
        // 日志拉取回传（§11.2）：模块 App 请求时回传 HLog 缓冲 + 熔断快照
        runCatching {
            com.lsp.hypersidebar.util.LogDumpBridge.register(
                ctx,
                statusProvider = { breaker.snapshot() },
                statsProvider = { StatsRecorder.dump() }
            )
        }.onFailure { HLog.w(TAG, "log dump register failed: ${it.message}") }
        // 壁纸变更监听（本进程的注册点是 init 的 DataLoader.onReady，异步且依赖数据源就绪；
        // 释放后不能指望它"早晚会再跑一次"，故在这里显式重挂。只挂监听、不重采壁纸）。
        runCatching { com.lsp.hypersidebar.util.WallpaperSampler.rearmHostSide(ctx) }
            .onFailure { HLog.w(TAG, "wallpaper rearm failed: ${it.message}") }
        // 数据记录（§11.3）：注入宿主 context（聚合结构落盘宿主本地 prefs）
        runCatching { com.lsp.hypersidebar.util.StatsRecorder.init(ctx) }
            .onFailure { HLog.w(TAG, "stats init failed: ${it.message}") }
    }

    /**
     * manifest 快捷方式 launcher 桥（批次 3，2026-09-05 实锤）：LauncherApps.getShortcuts
     * 对非默认桌面抛 SecurityException，而本进程恰是默认桌面（有访问权）。收设置页
     * REQUEST → 查询 → JSON 应答给模块 App 的 ShortcutRelayReceiver（显式组件寻址+
     * 数据仅驱动选择器展示，伪造危害=列表造假，仍按令牌严格校验。
     */
    private fun registerManifestShortcutsBridge(ctx: Context, via: String) {
        // 幂等：attach（冷启动）与热重载重挂共用本函数，重复进来自会多注册两份接收器
        if (manifestShortcutsReceiver != null || shortcutLaunchReceiver != null) return
        runCatching {
            ctx.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        Thread {
                            runCatching {
                                val la = c.getSystemService(
                                    android.content.pm.LauncherApps::class.java
                                ) ?: return@runCatching
                                // 2026-09-08 扩展：+DYNAMIC/PINNED（微信扫一扫/收付款等
                                // runtime 推送项 dumpsys 实锤全为 dynamic，原 MANIFEST-only
                                // 导致微信列表为空）。manifest 项走原 COMPONENT 路径（t=m），
                                // 动态/固定项走 startShortcut 代发（t=d，s=shortcutId）——
                                // 仅默认桌面可调 startShortcut，本进程恰是其宿主
                                val arr = org.json.JSONArray()
                                val seen = java.util.HashSet<String>()
                                val user = android.os.Process.myUserHandle()
                                // 两遍式查询：分类由 query 旗标决定而非读 ShortcutInfo 旗标位
                                //（manifest 项走原 COMPONENT 路径 t=m；动态/固定项 t=d，
                                // startShortcut 代发——它同样能启 manifest 项，误分类也安全）
                                fun queryShortcuts(flags: Int, isManifest: Boolean) {
                                    val q = android.content.pm.LauncherApps.ShortcutQuery()
                                        .setQueryFlags(flags)
                                    la.getShortcuts(q, user).orEmpty().forEach { si ->
                                        val key = si.`package` + "/" + si.id
                                        if (!seen.add(key)) return@forEach
                                        val label = si.longLabel?.toString()
                                            ?: si.shortLabel?.toString().orEmpty()
                                        if (isManifest) {
                                            val cn = si.activity ?: return@forEach
                                            arr.put(
                                                org.json.JSONObject()
                                                    .put("p", cn.packageName)
                                                    .put("c", cn.className)
                                                    .put("l", label)
                                                    .put("t", "m")
                                            )
                                        } else {
                                            arr.put(
                                                org.json.JSONObject()
                                                    .put("p", si.`package`)
                                                    .put("s", si.id)
                                                    .put("l", label)
                                                    .put("t", "d")
                                            )
                                        }
                                    }
                                }
                                queryShortcuts(
                                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                                    true
                                )
                                queryShortcuts(
                                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                                        android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                                    false
                                )
                                val reply = Intent(PrefKeys.MANIFEST_SHORTCUTS_REPLY)
                                    .setClassName(
                                        com.lsp.hypersidebar.util.FreeformLauncher.MODULE_PACKAGE,
                                        com.lsp.hypersidebar.ShortcutRelayReceiver::class.java.name
                                    )
                                    .putExtra(PrefKeys.MANIFEST_SHORTCUTS_EXTRA, arr.toString())
                                RelayToken.attach(reply, RelayToken.read(remotePrefs))
                                c.sendBroadcast(reply)
                                HLog.i(TAG, "manifest shortcuts replied: ${arr.length()}")
                            }.onFailure {
                                HLog.w(TAG, "manifest shortcuts query failed: ${it.message}")
                            }
                        }.start()
                    }
                }.also { manifestShortcutsReceiver = it },
                IntentFilter(PrefKeys.MANIFEST_SHORTCUTS_REQUEST),
                Context.RECEIVER_EXPORTED
            )
            HLog.i(TAG, "manifest shortcuts request receiver registered (via $via)")
        }.onFailure { HLog.e(TAG, "manifest shortcuts bridge register failed: ${it.message}") }

        // 动态/固定快捷方式 startShortcut 代发（2026-09-08）：有序广播进本进程（默认桌面，
        // 桌面角色现成——B2 归档"startShortcut 需桌面角色"的前提在此成立而非阻塞），
        // resultCode 1=已启动 0=失败/令牌拒绝。防伪=令牌严格档（借桌面身份启动任意
        // shortcut，与 root 代发同危害级）
        runCatching {
            ctx.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val pkg = intent.getStringExtra(PrefKeys.SHORTCUT_ID_LAUNCH_EXTRA_PKG) ?: return
                        val sid = intent.getStringExtra(PrefKeys.SHORTCUT_ID_LAUNCH_EXTRA_ID) ?: return
                        val pending = goAsync()
                        Thread {
                            var ok = false
                            var why = ""
                            runCatching {
                                val expected = RelayToken.read(remotePrefs)
                                val got = intent.getStringExtra(
                                    com.lsp.hypersidebar.prefs.PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN
                                )
                                if (expected.isNullOrEmpty() || got.isNullOrEmpty() || got != expected) {
                                    why = "token rejected"
                                } else if (!pkg.matches(PKG_NAME_REGEX)) {
                                    why = "malformed pkg"
                                } else {
                                    val la: android.content.pm.LauncherApps? = c.getSystemService(
                                        android.content.pm.LauncherApps::class.java
                                    )
                                    val user: android.os.UserHandle = android.os.Process.myUserHandle()
                                    if (la == null) {
                                        why = "LauncherApps unavailable"
                                    } else {
                                        // API 35+ 起 startShortcut 返回 void，失败抛异常
                                        //（runCatching 外层兜住 SecurityException 等）
                                        la.startShortcut(
                                            pkg, sid, null as android.graphics.Rect?,
                                            null as android.os.Bundle?, user
                                        )
                                        ok = true
                                    }
                                }
                            }.onFailure { why = "exception: ${it.message}" }
                            HLog.i(TAG, "startShortcut launch: pkg=$pkg id=$sid ok=$ok $why")
                            pending.resultCode = if (ok) 1 else 0
                            pending.finish()
                        }.start()
                    }
                }.also { shortcutLaunchReceiver = it },
                IntentFilter(PrefKeys.SHORTCUT_ID_LAUNCH_REQUEST),
                Context.RECEIVER_EXPORTED
            )
            HLog.i(TAG, "shortcut-id launch receiver registered (via $via)")
        }.onFailure { HLog.e(TAG, "shortcut-id launch register failed: ${it.message}") }
    }

    /**
     * API 102 热重载释放（旧代退休前由 `XposedInit.onHotReloading` 调用；幂等）。
     *
     * 为什么必须：launcher 进程的模块资源有六类全都由宿主长期持有，不会随旧代消失：
     *  ① 探针接收器（设置页 ping 的应答端）；
     *  ② manifest 快捷方式查询接收器 + startShortcut 代发接收器；
     *  ③ [ConfigSync] / [LogDumpBridge] 在本进程注册的接收器；
     *  ④ [WallpaperSampler] 的壁纸变更接收器；
     *  ⑤ 扇形悬浮窗（`ComposeFanHost` 的窗口 + Compose composition + lifecycle）；
     *  ⑥ 停顿定时器 / 待呼出 show / 主线程排队回调（都是**旧代闭包**，不清则旧代
     *     一直被钉住；停顿定时器尤其危险——旧代定时器到点会去呼出旧代的扇形窗口）。
     *
     * 只释放资源与注册，**不动 native**（见 `XposedInit.onHotReloaded` 的说明）。
     * 任一步失败只 `HLog.w`：释放不完整不该让热重载整体失败。
     */
    override fun releaseForReload() {
        // 定时器与待执行回调（先撤，避免释放过程中被旧代回调打断）
        runCatching {
            cancelPendingShow()
            cancelStallTimer()
            stallTimerHandler?.removeCallbacksAndMessages(null)
            mainHandler.removeCallbacksAndMessages(null)
        }.onFailure { HLog.w(TAG, "timer cleanup failed: ${it.message}") }
        // 本进程注册的接收器
        runCatching {
            val c = registeredCtx
            if (c != null) {
                probeReceiver?.let { c.unregisterReceiver(it) }
                manifestShortcutsReceiver?.let { c.unregisterReceiver(it) }
                shortcutLaunchReceiver?.let { c.unregisterReceiver(it) }
            }
        }.onFailure { HLog.w(TAG, "receiver unregister failed: ${it.message}") }
        probeReceiver = null
        manifestShortcutsReceiver = null
        shortcutLaunchReceiver = null
        registeredCtx = null
        probeRegistered = false
        // 共享通道（本进程各自注册了一份）
        runCatching { com.lsp.hypersidebar.util.ConfigSync.releaseForReload() }
            .onFailure { HLog.w(TAG, "config sync release failed: ${it.message}") }
        runCatching { com.lsp.hypersidebar.util.LogDumpBridge.releaseForReload() }
            .onFailure { HLog.w(TAG, "log dump release failed: ${it.message}") }
        runCatching { com.lsp.hypersidebar.util.WallpaperSampler.releaseForReload() }
            .onFailure { HLog.w(TAG, "wallpaper release failed: ${it.message}") }
        // 扇形悬浮窗：窗口/composition 都绑在旧代 classloader 上。控制器若从未初始化过
        // 就不要在这里把它初始化出来（isInitialized 判定）。
        runCatching {
            if (fanLazy.isInitialized()) fanLazy.value.releaseForReload()
        }.onFailure { HLog.w(TAG, "fan controller release failed: ${it.message}") }
        HLog.i(TAG, "releaseForReload: receivers/timers/fan window released (launcher)")
    }

    /** 记录层：触摸流入口，BeforeHook。返回 true = 消费（拦截原生处理）。 */
    private fun hookOnTouchEvent(): Boolean {
        val method = MethodFinder.fromClass(STUB_CLASS)
            .filterByName("onTouchEvent")
            .filterByParamTypes(MotionEvent::class.java)
            .filterByReturnType(Boolean::class.java)
            .firstOrNull() ?: run {
            HLog.e(TAG, "onTouchEvent NOT FOUND on $STUB_CLASS")
            return false
        }
        method.createBeforeHook {
            // 底角重放回流守卫（replayCornerGesture 的合成事件也会经本钩子）。
            // 2026-10-16 起角区也能从侧条窗口进入（见 handleTouch 的 DOWN 支），所以这条
            // 以前只有 NavStubView 钩子才需要的守卫，本钩子同样必须有——否则角区放弃时的
            // 重放会再次落回 handleTouch，形成递归。
            if (cornerReplaying) return@createBeforeHook
            val ev = it.args[0] as? MotionEvent ?: return@createBeforeHook
            val stub = it.thisObject as? View
            if (handleTouch(ev, stub)) it.result = true
        }
        return true
    }

    /**
     * 底角斜滑入口（N1）：hook `NavStubView.onTouchEvent`。
     * DOWN 命中底角区 → 接管整条手势（含触发失败的尾巴），beforeHook 置 result=true
     * 压掉原生 `startRecentsAnimationPre`（spike S4 实锤）；非底角一律放行。
     */
    private fun hookNavBottomGesture(): Boolean {
        val method = MethodFinder.fromClass(NAV_STUB_CLASS)
            .filterByName("onTouchEvent")
            .filterByParamTypes(MotionEvent::class.java)
            .filterByReturnType(Boolean::class.java)
            .firstOrNull() ?: run {
            HLog.e(TAG, "onTouchEvent NOT FOUND on $NAV_STUB_CLASS（底角手势不可用）")
            return false
        }
        method.createBeforeHook {
            // 重放进行中：缓冲事件经 dispatchTouchEvent 回流，本次 hook 直接放行（防递归）
            if (cornerReplaying) return@createBeforeHook
            val ev = it.args[0] as? MotionEvent ?: return@createBeforeHook
            val view = it.thisObject as? View
            if (handleCornerTouch(ev, view)) it.result = true
        }
        HLog.i(TAG, "corner gesture hooked: $NAV_STUB_CLASS.onTouchEvent")
        return true
    }

    /* 2026-10-16 删除 hotSpacePx(view)：热区高度三 ROM 统一走 GestureThresholds.CORNER_BAND_DP。
     * 原实现反射 NavStubView.getHotSpaceHeight() 取 ROM 真值（实测=25dp），而 OS4 的
     * SystemUiInputSpy 用常量 ⇒ 三 ROM 各一份来源。角区已实测与系统导航热区 0 重叠
     * （os4-hook-point-survey.md §6.2），"对齐 ROM 热区"不再是安全线，故统一为常量。 */

    /**
     * 底角手势状态机（NavStubView 入口）。返回 true = 消费（压掉原生）。
     *
     * 冻结语义（N1 定稿）：接管后一路消费到 UP/CANCEL，**禁止** DOWN 消费而 MOVE 放行
     * （原生会把 MOVE 当 DOWN 再次触发 `startRecentsAnimationPre`）。关闭态完全透传。
     */
    private fun handleCornerTouch(ev: MotionEvent, view: View?): Boolean {
        if (DataDeadState.dead) {
            if (cornerTrigger.claimed) cornerTrigger.reset()
            return false
        }
        if (!moduleEnabled()) {
            if (cornerTrigger.claimed) cornerTrigger.reset()
            return false
        }
        // fan 展示中：事件转发给 fan 并消费；UP/CANCEL 收起（与边缘通道同款）
        if (fanController.isShowing) {
            if (!fanSeenThisGesture) {
                val down = MotionEvent.obtain(
                    ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, ev.rawX, ev.rawY, 0
                )
                try { fanSeenThisGesture = fanController.dispatchTouchEvent(down) } finally { down.recycle() }
            }
            // 流驱动旗标（与 :ui 条上通道同款）：孤儿防护对呼出前起始的手势流放行——
            // OS2/3 拖选的 downTime < shownUptime 恒真，无旗标则 UP 被吞、选中永无法
            // 提交（1006 merlin 实锤：每次拖选都 orphan UP swallowed 后直接收起）
            com.lsp.hypersidebar.ui.fan.B2StreamDispatch.active = true
            try {
                fanController.dispatchTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    // 常驻模式：松手未提交选中 → 转点选态（窗口保留）；现状=立即收起。
                    // UP 已提交选中时 LAUNCHED 收起在先、isShowing=false，两分支都自然跳过
                    if (fanController.isShowing) {
                        if (fanPersistentOnRelease()) fanController.onGestureEnded()
                        else fanController.dismiss()
                    }
                    resetGesture()
                }
            } finally {
                com.lsp.hypersidebar.ui.fan.B2StreamDispatch.active = false
            }
            return true
        }
        // 底角功能关（默认）：完全透传（读走 SyncedPrefs 内存缓存）
        if (!cornerSwipeEnabled()) {
            if (cornerTrigger.claimed) cornerTrigger.reset()
            return false
        }
        // 熔断门：新手势不再接管；手动重试仅在熔断态检查（DOWN 高频路径不碰）
        if (breaker.open) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) breaker.maybeManualReset()
            if (breaker.open) {
                if (cornerTrigger.claimed) cornerTrigger.reset()
                return false
            }
        }

        val action = ev.actionMasked
        val dm = (view?.context ?: safeAppContext())?.resources?.displayMetrics
        val w = dm?.widthPixels?.toFloat() ?: 0f
        val h = dm?.heightPixels?.toFloat() ?: 0f

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                cornerSeq++
                gestureSeq++
                fanSeenThisGesture = false
                // 呼出确认距离：与边缘通道同源（TRIGGER_MIN_DISTANCE dp），DOWN 一次缓存整条手势
                dm?.let {
                    val distanceDp = runCatching {
                        remotePrefs.getFloat(
                            PrefKeys.TRIGGER_MIN_DISTANCE, LayoutDefaults.TRIGGER_MIN_DISTANCE_DP
                        )
                    }.getOrDefault(LayoutDefaults.TRIGGER_MIN_DISTANCE_DP)
                    confirmPx = distanceDp * it.density
                    resetPx = confirmPx * GestureThresholds.SWIPE_RESET_RATIO
                }
                // 底角参数：2026-10-16 起不再是设置项（实测不决定角区成败：系统在自己的
                // 导航热区之外照样 pilfer 抢流），取 GestureThresholds 常量——与 OS4 spy
                // 完全同源同值，不会再出现"OS2/3 与 OS4 各一份"的死带。
                // density 先提上来：热区高度（下一步）也要用它。
                val density = dm?.density ?: 1f
                // 热区高度统一为常量（原 hotSpacePx(view) 反射 NavStubView.getHotSpaceHeight）
                val hot = GestureThresholds.CORNER_BAND_DP * density
                val widthRatio = GestureThresholds.CORNER_WIDTH_RATIO
                val minDeg = GestureThresholds.CORNER_MIN_ANGLE_DEG
                val maxDeg = GestureThresholds.CORNER_MAX_ANGLE_DEG
                val inwardMinPx = GestureThresholds.CORNER_INWARD_MIN_DP * density
                val claimed = cornerTrigger.onDown(
                    ev.rawX, ev.rawY, w, h, hot,
                    w * widthRatio,
                    confirmPx,
                    inwardMinPx,
                    minDeg,
                    maxDeg,
                    GestureThresholds.CORNER_ABANDON_EXTRA_DEG,
                    GestureThresholds.CORNER_CONFIRM_FRAMES
                )
                if (claimed) {
                    cornerReplayBuffer.clear()
                    cornerReplayBuffer += CornerEventSnapshot(
                        ev.downTime, ev.eventTime, ev.actionMasked, ev.rawX, ev.rawY
                    )
                }
                vlog(
                    "c#$cornerSeq DOWN raw=(${ev.rawX.toInt()},${ev.rawY.toInt()}) " +
                        "screen=(${w.toInt()},${h.toInt()}) hot=${hot.toInt()} claimed=$claimed " +
                        "width=${(w * widthRatio).toInt()} cone=[$minDeg,$maxDeg] inwardMin=${inwardMinPx.toInt()}"
                )
                return claimed
            }

            MotionEvent.ACTION_MOVE -> {
                when (cornerTrigger.onMove(ev.rawX, ev.rawY)) {
                    CornerTrigger.Action.SHOW -> {
                        HLog.i(
                            TAG,
                            "c#$cornerSeq corner swipe SHOW raw=(${ev.rawX.toInt()},${ev.rawY.toInt()})"
                        )
                        StatsRecorder.onStall()
                        cornerReplayBuffer.clear()
                        fanSeenThisGesture = false
                        // 底角专用几何：锚点=精确底角、半径固定取竖屏设置、弧占向上象限、
                        // 快捷栏在上缘之上（N1 option B，用户定案）
                        postShowFanCorner(view)
                        return true
                    }
                    CornerTrigger.Action.ABANDON_REPLAY -> {
                        // 明确竖直（非斜滑意图）：把缓冲的 DOWN+MOVE 按原时序重放给原生
                        // （合成事件经 dispatchTouchEvent 回流，hook 侧 cornerReplaying 放行；
                        // 重放失败安全回退=保持消费）。之后实时事件经 abandoned→PASS 透传。
                        replayCornerGesture(view)
                        return false
                    }
                    CornerTrigger.Action.CONSUME -> {
                        // 判定中：缓冲快照（重放用）；触发成功时清空（扇形已接管后续）
                        cornerReplayBuffer += CornerEventSnapshot(
                            ev.downTime, ev.eventTime, ev.actionMasked, ev.rawX, ev.rawY
                        )
                        return true
                    }
                    CornerTrigger.Action.PASS -> return false
                }
            }

            MotionEvent.ACTION_POINTER_DOWN ->
                return cornerTrigger.onPointerDown() == CornerTrigger.Action.CONSUME

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 已重放（竖直尾巴）：native 正在处理重放手势，UP 自然透传完成它
                val replayed = cornerTrigger.abandoned && !cornerTrigger.consumedToEnd
                val consume = !replayed && cornerTrigger.onEnd() == CornerTrigger.Action.CONSUME
                // 未展示前松手（触发后主线程装配尚未落地）：撤销排队的 show（"未预选松手即收起"）；
                // 常驻模式改为照常落地并进点选态（同边缘通道 stallFired 分支）
                if (consume && !fanController.isShowing) {
                    if (fanPersistentOnRelease() && pendingShow != null) fanController.markReleasedOnArrival()
                    else cancelPendingShow()
                }
                vlog("c#$cornerSeq UP/CANCEL consume=$consume replayed=$replayed shown=${fanController.isShowing}")
                cornerTrigger.reset()
                cornerReplayBuffer.clear()
                return consume
            }

            // 已接管手势的其余事件（POINTER_UP 等）一律消费，绝不中途放行
            else -> return cornerTrigger.claimed && !cornerTrigger.abandoned
        }
        return cornerTrigger.claimed && !cornerTrigger.abandoned
    }

    /**
     * 锥外早退重放（2026-10-01）：把缓冲的 DOWN+MOVE 按原时序合成后喂回 NavStubView。
     * 原生处理器从未见过这些事件（hook 消费发生在 onTouchEvent 之前），收到的是干净的
     * 新手势起点——修复"底角起手的竖直上滑被吞、回桌面/后台白做"。重放事件经
     * dispatchTouchEvent 回流时由 [cornerReplaying] 标志放行（防 BeforeHook 递归）；
     * 失败安全回退=保持消费（现状行为，手势仍会丢但不至于撕裂）。
     */
    private fun replayCornerGesture(view: View?) {
        val v = view ?: run {
            cornerReplayBuffer.clear()
            return
        }
        cornerReplaying = true
        try {
            for (s in cornerReplayBuffer) {
                val e = MotionEvent.obtain(s.downTime, s.eventTime, s.action, s.x, s.y, 0)
                try { v.dispatchTouchEvent(e) } finally { e.recycle() }
            }
            HLog.i(TAG, "c#$cornerSeq replay ${cornerReplayBuffer.size} buffered events to native (vertical tail)")
        } catch (e: Throwable) {
            HLog.w(TAG, "corner replay failed: ${e.message} — falling back to consume-to-end")
        } finally {
            cornerReplayBuffer.clear()
            cornerReplaying = false
        }
    }

    /**
     * 拦截层：停顿标志命中时翻转 shouldBack → 原生走 onBackCancelled 复位分支。
     * 主路 = 实测混淆名 `$3` 直连；类名漂移（匿名类数字索引随混淆轮次变动）时按
     * 接口契约扫描兜底（1C 新写，0.x 仅识别未实现）：遍历 GestureStubView 全部
     * 内部类，命中"声明 onSwipeStop 且首参 Boolean"者即拦截回调。
     *
     * 类解析必须走宿主 classloader（EzXHelper ClassLoaderProvider，MethodFinder.fromClass
     * 内部同源）：裸 Class.forName 用的是模块自身 classloader，看不见宿主类——1C 轮一
     * 实测踩坑：直连误判 NOT FOUND + 扫描报 GestureStubView missing，拦截层静默失效
     * （呼出扇形后返回事件不被消费，原生返回直通执行）。
     */
    private fun hookOnSwipeStop(): Boolean {
        val cl = ClassLoaderProvider.safeClassLoader
        val stubClass = runCatching { Class.forName(STUB_CLASS, false, cl) }.getOrNull() ?: run {
            HLog.e(TAG, "onSwipeStop: cannot resolve $STUB_CLASS via host classloader")
            return false
        }
        val direct = runCatching { hookSwipeStopCallback(Class.forName(CALLBACK_CLASS, false, cl), "direct") }
            .getOrDefault(false)
        if (direct) return true
        HLog.w(TAG, "onSwipeStop NOT FOUND on $CALLBACK_CLASS, scanning GestureStubView inner classes")
        var hooked = 0
        for (inner in stubClass.declaredClasses) {
            val ok = runCatching { hookSwipeStopCallback(inner, "scan") }.getOrDefault(false)
            if (ok) hooked++
        }
        if (hooked == 0) {
            HLog.e(TAG, "scan fallback: no onSwipeStop(Boolean,...) in any inner class")
            return false
        }
        HLog.i(TAG, "scan fallback: $hooked onSwipeStop callback(s) hooked")
        return true
    }

    /** @return true = 该类声明 onSwipeStop(Bool First, ...) 且已挂 before hook */
    private fun hookSwipeStopCallback(cls: Class<*>, via: String): Boolean {
        val method = MethodFinder.fromClass(cls)
            .filterByName("onSwipeStop")
            .firstOrNull() ?: return false
        if (method.parameterTypes.firstOrNull() != Boolean::class.javaPrimitiveType) return false
        method.createBeforeHook {
            if (stallFired || fanController.isShowing) {
                val shouldBack = it.args[0] as? Boolean ?: return@createBeforeHook
                if (shouldBack) {
                    it.args[0] = false
                    vlog("g#$gestureSeq onSwipeStop($via) INTERCEPTED: shouldBack=true -> false")
                }
            }
        }
        HLog.i(TAG, "onSwipeStop hooked ($via): ${cls.name}")
        return true
    }

    private fun handleTouch(ev: MotionEvent, stub: View?): Boolean {
        // 数据源死亡停摆（迭代四 §1.3）：整条透传原生（原生返回优先），不再呼出
        if (DataDeadState.dead) {
            if (fanController.isShowing) fanController.dismiss(DismissCause.PREEMPTED)
            resetGesture()
            return false
        }
        // 总开关门（设置页"启用超级侧边栏"）：关闭=整条透传原生（同数据源死亡语义），
        // 展示中的 fan 立即收起；重新打开即时恢复（SyncedPrefs 读=内存缓存命中）
        if (!moduleEnabled()) {
            if (fanController.isShowing) fanController.dismiss(DismissCause.PREEMPTED)
            resetGesture()
            return false
        }
        // 底角已认领本手势（从侧条窗口进入，见 DOWN 支）：整条事件流交给角区状态机——
        // 与 NavStubView 入口完全是同一条路径（handleCornerTouch 自带 fan 转发、锥外重放
        // 与收尾）。它返回 false 时=放手让原生走（典型是锥外重放后的尾巴），此时同样透传，
        // 符合"角区放弃即让路"。放在 fan 转发块**之前**，让角区从 DOWN 到 UP 独占整条流，
        // 避免两套收尾逻辑交叉（否则 cornerTrigger 会留下未复位的 claimed 状态）。
        if (cornerOwnsGesture) {
            val consumed = handleCornerTouch(ev, stub)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                cornerOwnsGesture = false
            }
            return consumed
        }

        // fan 展示中：事件转发给 fan 并消费；UP/CANCEL 收起（未预选立即收起，PRD §7.1）
        if (fanController.isShowing) {
            if (!fanSeenThisGesture) {
                // fan 在手势中途异步落地：先合成 DOWN 起始选择状态（同 :ui 条上通道）。
                // 仅在真正送达时才置位——showInternal 主线程阻塞期间 host==null 会静默丢事件，
                // 实测合成 DOWN 被丢后整条手势再无 DOWN：窗口原点捕获、选中起点全部失效，
                // 整条手势退回 94px 窗口 inset 偏差（g#6 实锤）
                val down = MotionEvent.obtain(
                    ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, ev.rawX, ev.rawY, 0
                )
                try { fanSeenThisGesture = fanController.dispatchTouchEvent(down) } finally { down.recycle() }
            }
            // 流驱动旗标（同底角通道）：孤儿防护放行呼出前起始的手势流，拖选 UP 不被吞
            com.lsp.hypersidebar.ui.fan.B2StreamDispatch.active = true
            try {
                fanController.dispatchTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    // 常驻模式：松手未提交选中 → 转点选态（窗口保留）；现状=立即收起。
                    // UP 已提交选中时 LAUNCHED 收起在先、isShowing=false，两分支都自然跳过
                    if (fanController.isShowing) {
                        if (fanPersistentOnRelease()) fanController.onGestureEnded()
                        else fanController.dismiss()
                    }
                    resetGesture()
                }
            } finally {
                com.lsp.hypersidebar.ui.fan.B2StreamDispatch.active = false
            }
            return true
        }

        // 区外手势整条透传（修 1A 缺陷：此前仅 DOWN 过滤，其后 MOVE 仍会进状态机确认/停顿，
        // 触发区外也能呼出——实测 y 超 2/3 界多处 inZone=false 仍 STALL）
        if (!gestureInZone && ev.actionMasked != MotionEvent.ACTION_DOWN) return false

        // 熔断门（1C 轮二）：放在 fan 转发之后——进行中的手势优雅收尾，新手势不再拦截。
        // 手动重试检查只在熔断态执行（remotePrefs 读走 binder 缓存，DOWN 高频路径不碰）
        if (breaker.open) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) breaker.maybeManualReset()
            if (breaker.open) return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 配置新鲜度检查（迭代六 §11.1）：过期才后台 bind 拉取，此处仅 volatile 读，
                // 不在呼出关键路径上；新配置下次读取生效
                (stub?.context ?: safeAppContext())?.let { ctx ->
                    com.lsp.hypersidebar.util.ConfigPullBridge.refreshIfStale(ctx)
                }
                downX = ev.rawX
                downY = ev.rawY
                gestureSeq++
                swipeConfirmed = false
                stallFired = false
                // 滑动距离换算（dp→px，DOWN 一次缓存整条手势；设置项即时经 ConfigSync 生效）
                (stub?.context ?: safeAppContext())?.resources?.displayMetrics?.let { dm ->
                    val distanceDp = try {
                        remotePrefs.getFloat(
                            PrefKeys.TRIGGER_MIN_DISTANCE, LayoutDefaults.TRIGGER_MIN_DISTANCE_DP
                        )
                    } catch (_: Exception) {
                        LayoutDefaults.TRIGGER_MIN_DISTANCE_DP
                    }
                    confirmPx = distanceDp * dm.density
                    resetPx = confirmPx * GestureThresholds.SWIPE_RESET_RATIO
                }
                // 触发区外：完全透传（原生返回正常走）；DOWN 全量记录（A2/A7/A8 数据源）
                val inZone = isInTriggerZone(ev.rawX, ev.rawY, stub)
                gestureInZone = inZone
                vlog("g#$gestureSeq DOWN raw=(${ev.rawX.toInt()},${ev.rawY.toInt()}) inZone=$inZone")
                if (!inZone) {
                    // 区外才试底角（2026-10-16）：竖屏触发带 [H/3, 2H/3] 与角区带 [H−hot, H]
                    // 不相交（merlin 实测 1464 < 2128）⇒ "区外才试角区"等价于"角区才试角区"，
                    // 返回手势路径逐字节不变。角区自己在 handleCornerTouch 里再判一次
                    // 角窗宽/热区带/角度锥，判不中就返回 false，本函数照常透传。
                    if (handleCornerTouch(ev, stub)) {
                        cornerOwnsGesture = true
                        HLog.i(TAG, "c#$cornerSeq claim from side strip raw=(${ev.rawX.toInt()},${ev.rawY.toInt()})")
                        return true
                    }
                    return false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // 停顿已触发（fan 装配中或已展示前）：
                // 消费所有后续事件，避免喂给原生状态机
                if (stallFired) return true

                val inward = inwardDx(ev.rawX)

                // 滑回边缘：整体重置（PRD 状态机"滑回边缘→待触发"；滞回=确认距离一半，
                // 修零滞回下近阈值悬停微漂整条清零——0913 取证 g#44/45）
                if (swipeConfirmed && inward < resetPx) {
                    vlog("g#$gestureSeq RESET slide-back (inward=${inward.toInt()}px < ${resetPx.toInt()})")
                    resetGesture()
                    return false
                }

                if (!swipeConfirmed) {
                    val dy = abs(ev.rawY - downY)
                    // PRD §9.5：距离达确认线（可配置，默认 15dp）且与水平方向夹角 ≤60°（atan2，弃用 0.x 的 dx>dy/2 近似）
                    val angle = Math.toDegrees(
                        Math.atan2(dy.toDouble(), inward.toDouble())
                    ).toFloat()
                    if (inward >= confirmPx && angle <= GestureThresholds.MAX_SWIPE_ANGLE_DEG) {
                        swipeConfirmed = true
                        anchorX = ev.rawX
                        anchorY = ev.rawY
                        speedWinT = ev.eventTime
                        vlog("g#$gestureSeq swipe confirmed: inward=${inward.toInt()}px (>= ${confirmPx.toInt()}) angle=${angle.toInt()}")
                        // 13.1：确认即挂停顿定时器（取代旧 anchorT 基准+事件内评估）
                        armStallTimer(stub)
                    }
                }

                if (swipeConfirmed && !stallFired) {
                    // fire 快照：定时器开火时手指已静止、无实时事件可抄（合成 UP 用）。
                    // 确认后的每个 MOVE 都刷新——开火用的是最后一次真实触碰的位置/时刻。
                    lastDownTime = ev.downTime
                    lastEventTime = ev.eventTime
                    lastRawX = ev.rawX
                    lastRawY = ev.rawY
                    // 速度判据（0915 三轮实测定案，见 GestureThresholds.STALL_MAX_SPEED_PX_S）：
                    // 只有"窗内平均速度仍高于阈值"才重启定时器。位移式判据会被缓慢持续漂移
                    // 周期性触发，把 dwell 整轮重置（横屏实测均值 661ms，且数值是 250 的整数倍）。
                    // 速度窗口同时把锚点刷成"settle 时刻的位置"，日志里的 anchor 更有意义。
                    val dt = ev.eventTime - speedWinT
                    if (dt >= GestureThresholds.STALL_SPEED_WINDOW_MS) {
                        val speed = hypot(ev.rawX - anchorX, ev.rawY - anchorY) * 1000f / dt
                        anchorX = ev.rawX
                        anchorY = ev.rawY
                        speedWinT = ev.eventTime
                        if (speed > GestureThresholds.STALL_MAX_SPEED_PX_S) restartStallTimer(stub)
                    }
                    // 达标判定在定时器 fire（fireStall）：手指静止后无 MOVE 也照常触发
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vlog("g#$gestureSeq UP stallFired=$stallFired shown=${fanController.isShowing}")
                cancelStallTimer()
                if (stallFired) {
                    if (fanController.isShowing) {
                        // 常驻模式：松手未提交选中 → 转点选态（窗口保留）；现状=立即收起。
                        // 实测轮七的旧语义：fan 失去手势驱动源后会常驻——常驻模式下这正是
                        // 期望行为，看门狗（10s）兜底收起
                        if (fanPersistentOnRelease()) fanController.onGestureEnded()
                        else {
                            cancelPendingShow()
                            fanController.dismiss()
                        }
                    } else if (fanPersistentOnRelease() && pendingShow != null) {
                        // fan 装配中就松手：照常落地并直接进点选态（呼出后常驻的边界场景）
                        fanController.markReleasedOnArrival()
                    } else {
                        cancelPendingShow()
                    }
                    resetGesture()
                    return true
                }
                resetGesture()
            }
        }
        return false
    }

    /** 停顿触发 → 主线程弹 fan（launcher 触摸回调在 MiuiMirror 输入线程，Compose 需主线程装配）。
     *  13.1 起由停顿定时器 fire 调用，事件参数不再需要（锚点取 downY 字段）。 */
    private fun postShowFan(stub: View?) {
        val ctx = safeAppContext() ?: stub?.context ?: run {
            HLog.w(TAG, "postShowFan: no context available")
            return
        }
        val dm = ctx.resources.displayMetrics
        // 圆心：X 固定在呼出起始侧的屏幕边缘，Y = 手指初始触碰边缘时的高度（downY，
        // DOWN 已被触发区过滤保证在带内；实测轮六定稿——停顿过程中的滑动漂移不再
        // 影响呼出位置）。展开方向由 computeFanGeometry 按锚点半边自动确定。
        // 圆心 Y 若超出几何层可行带会被 computeFanGeometry 二次钳制（保形恒在屏内）。
        // 横屏区已置空（B 路线），空区间时跳过钳制防 coerceIn 抛异常（横屏本就到不了这里）
        val (zoneTop, zoneBottom) = zoneBounds(dm)
        val anchorX = if (downX < dm.widthPixels / 2f) 0f else dm.widthPixels.toFloat()
        val anchorY = if (zoneTop < zoneBottom) downY.coerceIn(zoneTop, zoneBottom) else downY
        HLog.i(TAG, "showFan: anchor=($anchorX, $anchorY) downY=$downY dwell=${dwellMs()}ms")
        // 耗时锚点（呼出卡顿归因）：postLag=launcher 主线程繁忙度——数值大说明
        // 呼出迟到是主线程排队，而不是装配慢
        val postAtMs = android.os.SystemClock.uptimeMillis()
        // source 冻结（C4，计划 §A3）：识别成立时刻取一次前台——launcher 进程自身
        // 在前台（竖屏边缘条由 launcher 接管）⇒ capture 过滤 HOME 后恒 null=不记录，
        // 第三方应用前台时才可能拿到值（binder ~1-2ms，红线明文例外）
        val frozenSource = ForegroundSourceProbe.capture(HostPackages.HOME)
        val r = Runnable {
            pendingShow = null
            vlog("g#$gestureSeq showFan runnable: postLag=${android.os.SystemClock.uptimeMillis() - postAtMs}ms")
            fanController.show(ctx, anchorX, anchorY, source = frozenSource)
        }
        pendingShow = r
        mainHandler.post(r)
    }

    /**
     * 底角触发 → 主线程弹 fan（锚点=精确底角 (0,H)/(W,H)，cornerAnchor 专用几何：
     * 半径固定取竖屏设置、弧占向上象限、快捷栏在上缘之上）。仅竖屏（CornerTrigger 已门控）。
     */
    private fun postShowFanCorner(view: View?) {
        val ctx = safeAppContext() ?: view?.context ?: run {
            HLog.w(TAG, "postShowFanCorner: no context available")
            return
        }
        val dm = ctx.resources.displayMetrics
        val anchorX = if (downX < dm.widthPixels / 2f) 0f else dm.widthPixels.toFloat()
        // 锚点 y 只要 ≥ 渲染端窗口底即可，最终位置由 FanGeometry 的窗口钳制定——
        // 窗口底=手势条 pill 上沿（merlin 0929 实机确认观感），才是真正的视觉安全线；
        // heightPixels/navigationBars insets 的语义都随设备漂移（marble=全屏高 2400、
        // merlin=可视高 2196、merlin 桌面 insets 被消费返回 0），且手势触摸区上沿≠pill
        // 上沿，按它们贴线会一头越 pill、一头留白回退。
        val anchorY = maxOf(
            gestureSafeBottomY(view) ?: 0f,
            dm.heightPixels.toFloat()
        )
        HLog.i(TAG, "showFan(corner): anchor=($anchorX, $anchorY) downX=$downX")
        val postAtMs = android.os.SystemClock.uptimeMillis()
        val frozenSource = ForegroundSourceProbe.capture(HostPackages.HOME)
        val r = Runnable {
            pendingShow = null
            vlog("c#$cornerSeq showFan(corner) runnable: postLag=${android.os.SystemClock.uptimeMillis() - postAtMs}ms")
            fanController.show(ctx, anchorX, anchorY, cornerAnchor = true, source = frozenSource)
        }
        pendingShow = r
        mainHandler.post(r)
    }

    /** 手势条上沿（屏幕 y）：宿主窗口底 − navigationBars insets 底部；拿不到 insets 返回 null。 */
    private fun gestureSafeBottomY(view: View?): Float? {
        val root = view?.rootView ?: return null
        val insets = runCatching { root.rootWindowInsets }.getOrNull() ?: return null
        val barBottom = runCatching {
            insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        }.getOrDefault(0)
        val loc = IntArray(2)
        runCatching { root.getLocationOnScreen(loc) }.onFailure { return null }
        return (loc[1] + root.height - barBottom).toFloat()
    }

    /**
     * 停顿触发后事件被消费，原生状态机收不到 UP——返回箭头/预测式返回效果残留。
     * GestureStubView.onBackCancelled() 只复位预测式回调（反编译实锤），箭头退出动画
     * 在 $3.onSwipeStop 完整路径里。修法：给原生触摸处理器喂合成 UP——走完整原生收尾
     * （$3.onSwipeStop 拦截层把 shouldBack 翻为 false → 箭头动画退出 + 预测式返回复位 +
     * 状态机复位），在与原生相同的线程（本 hook 的输入线程）同步调用。
     *
     * **字段名跨 ROM 漂移**（2026-09-26 三代 MiuiHome 离线实证）：
     *
     * | ROM | 字段 | 处理器类 |
     * |---|---|---|
     * | OS3.318 | `mGesturesBackTouchProcessor` | `GesturesBackTouchProcessor` |
     * | **OS2.0.215** | **`mGesturesBackController`** | **`GesturesBackController`** |
     * | OS4 | GestureStubView 不在 dex（native/Rust launcher） | — |
     *
     * 关键：**方法签名三代一致**（`onPointerEvent(MotionEvent, GestureStubView):void`），
     * 只有字段名与类名被重命名 ⇒ 按候选名优先、再按"字段类型声明了 onPointerEvent"结构化
     * 兜底，即可同时覆盖两代，且未来再改名也能自愈。
     */
    private fun cancelNativeGestureSnapshot(stub: View?) {
        if (stub == null) return
        runCatching {
            val processor = resolveGestureProcessor(stub) ?: run {
                if (!processorMissingLogged) {
                    processorMissingLogged = true
                    HLog.w(TAG, "gesture processor field unresolved → native teardown skipped")
                }
                return
            }
            val method = findOnPointerEvent(processor.javaClass, stub.javaClass) ?: run {
                HLog.w(TAG, "onPointerEvent not found on ${processor.javaClass.name}")
                return
            }
            method.isAccessible = true
            val up = MotionEvent.obtain(
                lastDownTime, lastEventTime, MotionEvent.ACTION_UP, lastRawX, lastRawY, 0
            )
            try { method.invoke(processor, up, stub) } finally { up.recycle() }
            HLog.i(TAG, "native gesture teardown via synthetic UP (${processor.javaClass.simpleName})")
        }.onFailure { HLog.w(TAG, "cancelNativeGesture failed: ${it.message}") }
    }

    /** 已知字段名（按 ROM 新旧排列），仅作快路径 —— 命中后仍要过 [findOnPointerEvent] 校验 */
    private val gestureProcessorFieldNames = listOf(
        "mGesturesBackController",          // OS2
        "mGesturesBackTouchProcessor",      // OS3（历史名，保留向后兼容）
    )

    /**
     * 解析 GestureStubView 上的"原生触摸处理器"字段值。
     * ① 候选名快路径（须通过指纹校验，避免"名字还在但语义变了"）；
     * ② 结构化兜底：遍历本类声明字段，取**类型声明了 `onPointerEvent(MotionEvent, <本类>)`** 的那个。
     * 结果按 stub 类缓存（每进程一次），失败不缓存（下次仍可重试）。
     */
    private fun resolveGestureProcessor(stub: View): Any? {
        val stubClass = stub.javaClass
        cachedProcessorField?.let { f ->
            if (f.declaringClass == stubClass) {
                return runCatching {
                    f.apply { isAccessible = true }.get(stub)
                }.getOrNull()
            }
        }
        // ① 候选名
        for (name in gestureProcessorFieldNames) {
            val f = runCatching { stubClass.getDeclaredField(name) }.getOrNull() ?: continue
            if (findOnPointerEvent(f.type, stubClass) != null) {
                cachedProcessorField = f
                HLog.i(TAG, "gesture processor field resolved by name: $name (${f.type.simpleName})")
                return runCatching { f.apply { isAccessible = true }.get(stub) }.getOrNull()
            }
        }
        // ② 结构化：字段类型是否声明 onPointerEvent(MotionEvent, 本类)
        for (f in stubClass.declaredFields) {
            if (findOnPointerEvent(f.type, stubClass) == null) continue
            cachedProcessorField = f
            HLog.i(TAG, "gesture processor field resolved structurally: ${f.name} (${f.type.simpleName})")
            return runCatching { f.apply { isAccessible = true }.get(stub) }.getOrNull()
        }
        return null
    }

    /**
     * 在 [owner] 上找 `onPointerEvent(MotionEvent, <第二参可接受 stubClass>)`。
     * 用参数形状而非精确 `getMethod`——第二参在三代都是 `GestureStubView` 本身，
     * 但按 `isAssignableFrom` 匹配可容忍未来换成其父类/接口。
     */
    private fun findOnPointerEvent(owner: Class<*>, stubClass: Class<*>): java.lang.reflect.Method? {
        val all = owner.declaredMethods.toList() + owner.methods.toList()
        return all.firstOrNull { m ->
            m.name == "onPointerEvent" && m.parameterTypes.size == 2 &&
                MotionEvent::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.parameterTypes[1].isAssignableFrom(stubClass)
        }
    }

    private fun cancelPendingShow() {
        val hadPending = pendingShow != null
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        pendingShow = null
        // 判据（2026-10-17 用户口径）：极快抡（起手到抬手 ~24–50ms）本来就可能是误触 ⇒ 保持
        // "不落地"的现状，但留一行能对账的证据，免得"为什么快滑没反应"只能靠猜。
        if (hadPending && lastDownTime > 0L) {
            val dur = android.os.SystemClock.uptimeMillis() - lastDownTime
            if (dur < 120L) HLog.i(TAG, "early-release dur=${dur}ms（误触过滤）")
        }
    }

    // ===== 停顿定时器（13.1）：确认挂上 → 速度超阈值重启 → UP/滑回/重置取消 → 到点开火 =====

    private fun ensureStallHandler(): android.os.Handler {
        stallTimerHandler?.let { return it }
        val looper = android.os.Looper.myLooper()
        stallTimerOnTouchThread = looper != null
        if (!stallTimerOnTouchThread) {
            HLog.w(TAG, "stall timer: touch thread has no looper, falling back to main " +
                "(synthetic UP will be skipped; onSwipeStop layer covers teardown)")
        }
        return android.os.Handler(looper ?: android.os.Looper.getMainLooper()).also { stallTimerHandler = it }
    }

    private fun armStallTimer(stub: View?) {
        cancelStallTimer()
        stallTimerSeq = gestureSeq
        val dwell = dwellMs()
        val r = Runnable { fireStall(stub) }
        stallTimer = r
        ensureStallHandler().postDelayed(r, dwell)
    }

    /** 速度窗内超速=手指仍在主动移动：dwell 全额重启（语义同旧 anchorT=eventTime 重置）。 */
    private fun restartStallTimer(stub: View?) {
        armStallTimer(stub)
        vlog("g#$gestureSeq STALL-TIMER restart ${dwellMs()}ms (speed > ${GestureThresholds.STALL_MAX_SPEED_PX_S.toInt()}px/s)")
    }

    private fun cancelStallTimer() {
        stallTimer?.let { (stallTimerHandler ?: mainHandler).removeCallbacks(it) }
        stallTimer = null
    }

    /** 定时器开火（触摸线程）：序号守卫防跨手势误触，fire 前重验手势仍有效。 */
    private fun fireStall(stub: View?) {
        if (stallTimer == null) return  // removeCallbacks 与在途任务竞态：已取消则静默退出
        stallTimer = null
        if (stallTimerSeq != gestureSeq || !swipeConfirmed || stallFired) return
        if (DataDeadState.dead || !moduleEnabled()) return
        stallFired = true
        StatsRecorder.onStall()
        vlog("g#$gestureSeq STALL-TIMER fire ${dwellMs()}ms anchor=(${anchorX.toInt()},${anchorY.toInt()})")
        if (stallTimerOnTouchThread) cancelNativeGestureSnapshot(stub)
        postShowFan(stub)
    }

    /** PRD 触发区（§9.5 行为规则 3 / §7.3.1）：竖屏 [H/3, 2H/3]；横屏=原小白条位置带。 */
    private fun isInTriggerZone(x: Float, y: Float, stub: View?): Boolean {
        val dm = (stub?.context ?: safeAppContext())?.resources?.displayMetrics ?: return false
        val (top, bottom) = zoneBounds(dm)
        return y in top..bottom
    }

    /** 触发区唯一定义源（DOWN 过滤与 postShowFan 钳制共用，避免两处各写一份漂移）。 */
    private fun zoneBounds(dm: DisplayMetrics): Pair<Float, Float> =
        if (dm.widthPixels > dm.heightPixels) {
            // B 路线（1B）：横屏触发整体移交 :ui cover 通道（隐藏条直接收事件，整条可达
            // 含条顶 [0,108px) 死区），launcher 侧横屏区置空防双触发；原生返回带恢复系统默认
            1f to 0f
        } else {
            (dm.heightPixels * GestureThresholds.TRIGGER_ZONE_TOP_RATIO) to
                (dm.heightPixels * GestureThresholds.TRIGGER_ZONE_BOTTOM_RATIO)
        }

    private fun inwardDx(x: Float): Float =
        if (downX < screenHalfWidth()) x - downX else downX - x

    private fun screenHalfWidth(): Float =
        safeAppContext()?.resources?.displayMetrics?.let { it.widthPixels / 2f }
            ?: downX  // 上下文不可用时以自身为界（保守：按左边缘处理）

    private fun resetGesture() {
        swipeConfirmed = false
        stallFired = false
        speedWinT = 0L
        fanSeenThisGesture = false
        // 角区所有权一并复位：本函数是"本帧起回到待触发态"的唯一收口，任何路径（含熔断/
        // 总闸/数据源死亡的中途早退）走到这里都不该再把后续事件当角区处理。
        cornerOwnsGesture = false
        cancelStallTimer()
    }

    private fun dwellMs(): Long = try {
        remotePrefs.getInt(PrefKeys.TRIGGER_DWELL_MS, LayoutDefaults.TRIGGER_DWELL_MS).toLong()
    } catch (_: Exception) {
        LayoutDefaults.TRIGGER_DWELL_MS.toLong()
    }

    /** 总开关（设置页"启用超级侧边栏"，PrefKeys.ENABLED）：关闭=本 hook 停止一切侵入。 */
    private fun moduleEnabled(): Boolean =
        runCatching { remotePrefs.getBoolean(PrefKeys.ENABLED, true) }.getOrDefault(true)

    /** 常驻扇形（设置页"呼出后常驻"，PrefKeys.FAN_PERSISTENT_ON_RELEASE）：
     *  开=松手未提交选中时扇形转点选态常驻；关=现状（未预选松手立即收起）。 */
    private fun fanPersistentOnRelease(): Boolean =
        runCatching {
            remotePrefs.getBoolean(PrefKeys.FAN_PERSISTENT_ON_RELEASE, LayoutDefaults.FAN_PERSISTENT_ON_RELEASE)
        }.getOrDefault(false)

    /** 底角斜滑开关（N1，默认关）：关闭=底角手势完全透传原生。 */
    private fun cornerSwipeEnabled(): Boolean = runCatching {
        remotePrefs.getBoolean(PrefKeys.CORNER_SWIPE_ENABLED, LayoutDefaults.CORNER_SWIPE_ENABLED)
    }.getOrDefault(LayoutDefaults.CORNER_SWIPE_ENABLED)
}
