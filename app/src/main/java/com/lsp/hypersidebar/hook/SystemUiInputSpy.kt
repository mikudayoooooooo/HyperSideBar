package com.lsp.hypersidebar.hook

import android.graphics.Rect
import android.os.Looper
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import com.lsp.hypersidebar.hook.CornerTrigger
import com.lsp.hypersidebar.prefs.PropNames
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.SysProps
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * B2 输入观察/转发通道（os4-trigger-final-plan §6）：
 *
 * B 路线的输入侧地基。OS4 桌面 Rust 手势区持有边缘输入所有权，:ui 把手窗口被饿死
 * （os4-trigger-input-ownership-report §2.3：4 份导出零 `touch:`）——恢复 OS3 触发
 * 必须在能拿到全屏输入的进程里自建观察点。SystemUI uid=1000 且自家
 * EdgeBackGestureHandler 就用同族 API（monitorInput spy + 命中后 pilfer），机制有
 * 平台级先例。monitor 本身只观察（原始目标照常收事件）；pilfer 只由 2a/空白接管显式发起：
 *
 *  - 每事件打点走 [HLog.verboseEnabled] 门控（60~120Hz MOVE 会刷爆环形缓冲）；
 *  - 计数器恒开：带内 DOWN / 底部手势带 DOWN（B2 排除清单的前置证据）。
 *
 * 全反射实现（monitorInput/getInputChannel 均 @hide），任一步失败=惰性退出并 W
 * （romer 可能裁剪；fail-closed 不比现状差）。
 *
 * **启动时序（1005 双时机探针定案）**：SystemUI 起来后 ~0.3s（Application.attach）建的
 * monitor 注册进 dispatcher 但**永久聋**（dumpsys 可见、零事件）；SystemUI 完全初始化后
 * 建的 monitor 事件正常。⇒ 不在 attach 时刻建：+5s 首建 + 15s 观察窗自愈（窗口内零 DOWN
 * =dispose 重建，直到 armed）。dispose 必须在 owner Looper 执行（Android 17 强制）——
 * 本对象的 monitor 活动全部在 [inputHandler] 的 Looper 上。
 *
 * **线程模型（2026-11-12，issue #12）**：
 *  - `hsb-spy-input`：InputEventReceiver 回调 + 流状态机（tracks/2a/合流/广播入队）；
 *  - SystemUI 主线程：空白点击/接管判定（[blankTapDispatch] → [maybeBlankTap]），
 *    `GestureTakeover`/`BlankTapDetector`/`blankAtDown` 只在这一条线程访问；
 *  - `hsb-spy-send`：把 [pendingStreams] 排空成 `sendBroadcast`（输入回调稳态零 binder）。
 *  消费方门控：[ensureConsumer] 每 2s 在主线程求值一次（`SpyInputPolicy.needed`），
 *  没有消费方就 dispose monitor——OS2/3 全默认时本进程**不存在**任何 spy monitor。
 *
 * **归属**：[inputThread]/[sendThread]、pilfer/屏幕状态接收器、monitor/receiver 均由本对象
 * 创建；[disposeForIdle]（无消费方）与 [releaseForReload]（热重载）负责释放。
 */
object SystemUiInputSpy {

    private const val TAG = "SysInputSpy"

    /** receiver 存在（spy 已构建，未必已 armed） */
    @Volatile private var started = false
    /** 首个事件已到达（投递链真正接通）——B2 的可用判据 */
    @Volatile var armed = false; private set
    @Volatile private var gaveUp = false
    private var attempts = 0
    /** 聋 monitor 重建轮次（自愈循环） */
    private var deafRebuilds = 0
    private var appContext: android.content.Context? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    /** 热重载/空闲释放后为 true；所有自续排的循环看到它就停。 */
    @Volatile private var released = false
    /** 已打过 "spy idle" 日志（每段空闲只打一次）。 */
    @Volatile private var idleLogged = false
    /** 有一次 build 在排队/重试中（防止 ensureConsumer 重复投递）。 */
    @Volatile private var buildInFlight = false
    /** 消费方轮询循环已启动。 */
    @Volatile private var consumerLoop = false

    /** 专用输入线程：receiver + 流状态机（主线程繁忙不再阻塞 monitor ack）。 */
    @Volatile private var inputThread: android.os.HandlerThread? = null
    @Volatile private var inputHandler: android.os.Handler? = null
    /** 专用广播线程：sendBroadcast 不再占输入回调/主线程。 */
    @Volatile private var sendThread: android.os.HandlerThread? = null
    @Volatile private var sendHandler: android.os.Handler? = null
    /**
     * 兜底：个别 ROM 上 `InputEventReceiver(channel, 非主 Looper)` 抛异常（计划 §Assumptions）。
     * 置 true 后 receiver 回到主线程；这将重新引入"主线程繁忙 → monitor ack 超时"的部分风险，
     * 日志会明确标注（`receiver thread=main (fallback)`）。
     */
    @Volatile private var receiverOnMain = false
    /** pilferPointers 与 dispose 互斥（2a 输入线程 / 空白接管主线程 / dispose 输入线程）。 */
    private val pilferLock = Any()

    /** 息屏/锁屏暂停 deaf 重建，屏幕点亮/解锁时立即恢复（2026-11-13）。 */
    private var screenReceiver: android.content.BroadcastReceiver? = null
    @Volatile private var deafPausedLogged = false
    private val deafCheckRunnable = Runnable { deafCheck() }
    /** 无消费方时释放 monitor 后，屏幕事件不应再触发重建。 */
    @Volatile private var screenReceiverCtx: android.content.Context? = null

    /** 消费方重查间隔（设置页改动/前台形态变化 ≤2s 内反映到 monitor 启停）。 */
    private const val CONSUMER_RECHECK_MS = 2_000L

    /**
     * pilfer 指令接收器 + 注册用的原始 ctx。
     *
     * ⚠ **必须存字段**（2026-10-16 热重载审计）：此前是 `ctx.registerReceiver(匿名对象, …)`，
     * 引用只存在于 AMS 的 receiver 表里——热重载时**无处注销** ⇒ 旧代 classloader 被钉住
     * （receiver 的类就是旧代的匿名类），而新代再注册一份就有两个接收器、同一条 pilfer
     * 指令处理两遍（pilfer 不可回滚，重复调用是实打实的风险）。
     */
    @Volatile private var pilferReceiver: android.content.BroadcastReceiver? = null
    @Volatile private var pilferCtx: android.content.Context? = null
    /** 兜底路径：ROM 拒绝带 Handler 的注册，receiver 落在主线程 ⇒ tracks 访问加锁。 */
    @Volatile private var pilferReceiverOnMain = false

    /** 构建失败重试（构造抛异常时；1004 教训：attach 极早期 context/display 未就绪） */
    private const val MAX_ATTEMPTS = 12
    private const val RETRY_MS = 5_000L

    /**
     * 自愈式启动参数（1005 双时机探针定案：+0.3s 建的 monitor 注册成功但永久聋
     * ——dumpsys 可见而事件零到达；+60s 建的事件正常。⇒ 不在 attach 时刻建 monitor，
     * 而是延迟首建 + 观察窗自愈：窗口内零 DOWN=判聋=dispose 重建，直到首个事件到达
     * （armed）。阈值无论落在启动后哪一秒，循环自然跨过；重建前 dispose 防泄漏。
     */
    private const val FIRST_BUILD_DELAY_MS = 5_000L
    /** 聋窗观察窗：**15s**（2026-10-17 回退 3s 的改动——真机实证 3s 会把"设备只是空闲、没有触摸"
     *  误判成聋：日志里 20:14 连续 `spy deaf after 3s → rebuild #3/#4/#5`，每 3s 重建一次通道。
     *  原始 15s 的语义是"建得太早的聋 monitor"（那是**永久**聋，15s 内零事件即可识别），
     *  空闲并不等于聋 ⇒ 观察窗不能短于"人自然停顿不摸屏"的时间尺度。 */
    private const val DEAF_CHECK_WINDOW_MS = 15_000L
    /** 息屏/锁屏期间不重建，只低频复查（屏幕点亮/解锁由 screen receiver 立即唤醒）。 */
    private const val DEAF_CHECK_SCREEN_OFF_MS = 60_000L
    /** 原"重建封顶"（2026-10-18 起不再放弃，见 [deafCheck] 注释）：现仅作日志降频阈值——
     *  超过此轮次后聋行每 4 轮打一条。 */
    private const val MAX_DEAF_REBUILDS = 12

    /** 取证类清单（api dump 用） */
    private val CANDIDATE_CLASSES = arrayOf(
        "android.hardware.input.InputManagerGlobal",
        "android.hardware.input.InputManager",
        "miui.hardware.input.MiuiInputManager",
    )

    /** 连接候选（类名, 方法名）：marble framework dex 反汇编实锤（framework-marble-os4.jar
     *  classes2.dex 方法表）=InputManagerGlobal.getInstance().monitorGestureInput(String,int):
     *  InputMonitor；InputManager.getInstance 已不存在。其余候选=旧 ROM 兜底。 */
    private val CANDIDATES = arrayOf(
        "android.hardware.input.InputManagerGlobal" to "monitorGestureInput",
        "android.hardware.input.InputManagerGlobal" to "monitorInput",
        "android.hardware.input.InputManager" to "monitorInput",
    )

    /** 恒开计数（B2 前置证据）：累计 DOWN / 底部 1/4 带 DOWN / 屏缘 40px 带 DOWN */
    @Volatile var totalDown = 0L; private set
    /** **当前这次 build** 收到的 DOWN 数（2026-11-13）：聋判据必须看它，不能看累计的
     *  [totalDown]——否则进程里只要有过一次触摸，之后每次重建都会在 15s 后被误判为 armed，
     *  真聋的 monitor 永远得不到自愈。 */
    private var buildDown = 0L
    @Volatile var bottomBandDown = 0L; private set
    @Volatile var edgeBandDown = 0L; private set

    /**
     * InputMonitor 强引用保活（1005 实锤：register 成功、channel 有效、receiver 就位，
     * 却零事件到达——monitor 对象在 openSpyChannel 返回后即不可达，GC 回收触发 native
     * dispose ⇒ dispatcher 摘除 spy 窗口。两个参考实现（MiuiBackGestureHook
     * NativeBackInputMonitor / AOSP ClipboardOverlayController）都把 monitor 存字段保活）。
     * 同时保 channel 引用（receiver 原生侧持有，双保险）。
     */
    @Volatile private var monitorRef: Any? = null
    @Volatile private var channelRef: android.view.InputChannel? = null
    /** receiver 三保活（参考工程 NativeBackInputMonitor/ContextualSearchInputReceiver
     *  均存字段；native 侧 Looper 注册持一层引用，双保险） */
    @Volatile private var receiverRef: InputEventReceiver? = null

    fun start(
        context: android.content.Context? = null,
        modulePrefs: android.content.SharedPreferences? = null,
    ) {
        if (started || gaveUp) return
        released = false
        if (prefs == null) prefs = modulePrefs
        // 存原始 context（attach 传的是 base ContextImpl）：1004 实测 applicationContext
        // 在 Application.attach 时刻仍为 null（LoadedApk 未挂完），存它导致 ctx 路径全程失联
        if (appContext == null) appContext = context
        // 1005 定案：attach 时刻（+0.3s）建的 monitor 永久聋 ⇒ 延迟首建（自愈循环起点）。
        // 2026-11-12：首建前先过消费方门（OS2/3 全默认 ⇒ 连 monitor 都不存在，issue #12）。
        if (!consumerLoop) {
            consumerLoop = true
            mainHandler.postDelayed({ ensureConsumer() }, FIRST_BUILD_DELAY_MS)
        }
    }

    /** 模块总闸（同 `LayoutDefaults.ENABLED` 语义；读失败按开）。 */
    private fun moduleEnabled(): Boolean =
        runCatching { prefs?.getBoolean(com.lsp.hypersidebar.prefs.PrefKeys.ENABLED, true) ?: true }
            .getOrDefault(true)

    /** 空白点击是否需要 monitor：动作层已装入 + 设置开启（与 [maybeBlankTap] 同键）。 */
    private fun blankTapActive(): Boolean =
        FreeformBlankTapHook.current != null && runCatching {
            prefs?.getBoolean(
                com.lsp.hypersidebar.prefs.PrefKeys.FREEFORM_BLANK_TAP_ENABLED,
                com.lsp.hypersidebar.prefs.LayoutDefaults.FREEFORM_BLANK_TAP_ENABLED,
            ) ?: false
        }.getOrDefault(false)

    /**
     * 消费方轮询（主线程，2s 一次，自续排）：
     * 没有消费方 ⇒ dispose 现有 monitor（OS2/3 全默认时完全不建）；
     * 有消费方且未建 ⇒ 投递一次 build（线程首次懒建）。
     */
    private fun ensureConsumer() {
        if (released || gaveUp) {
            consumerLoop = false
            return
        }
        val m = moduleEnabled()
        val os4 = Os4Marker.romIsOs4()
        val cs = cornerSwipeEnabled()
        val fb = cornerFallbackActive()
        val bt = blankTapActive()
        val need = SpyInputPolicy.needed(m, os4, cs, fb, bt)
        if (!need && started) {
            inputHandler?.post { disposeForIdle() }
        } else if (need && !started) {
            if (!buildInFlight) {
                idleLogged = false
                buildInFlight = true
                HLog.i(TAG, "spy consumer present ⇒ build (os4=$os4 module=$m " +
                    "cornerSwipe=$cs fallback=$fb blankTap=$bt)")
                ensureThreads()
                val hIn = inputHandler
                if (hIn == null) {
                    buildInFlight = false
                    HLog.w(TAG, "spy build skipped: input thread failed to start")
                } else {
                    hIn.post { attemptStart() }
                }
            }
        } else if (!need && !idleLogged) {
            idleLogged = true
            HLog.i(TAG, "spy idle: no consumer (os4=$os4 module=$m cornerSwipe=$cs " +
                "fallback=$fb blankTap=$bt)")
        }
        mainHandler.postDelayed({ ensureConsumer() }, CONSUMER_RECHECK_MS)
    }

    /** 建输入线程（幂等；只由 ensureConsumer 在需要 monitor 时调用）。 */
    @Synchronized
    private fun ensureThreads() {
        if (inputThread?.isAlive != true) {
            val t = android.os.HandlerThread("hsb-spy-input").apply { start() }
            inputThread = t
            // looper 访问会等 looper 就绪（HandlerThread 契约），此后可安全 post。
            inputHandler = android.os.Handler(t.looper)
        }
        ensureScreenReceiver()
    }

    /**
     * 屏幕点亮/解锁时立即恢复 deaf 检查；息屏/锁屏期间不重建 monitor（2026-11-13）。
     * receiver 注册在输入线程，回调里直接操作 `deafCheckRunnable`，与自愈检查同线程。
     * 只在 monitor 真正需要时（ensureThreads 被调用）注册；释放见 [releaseForReload]。
     */
    private fun ensureScreenReceiver() {
        if (screenReceiver != null) return
        val ctx = appContext ?: return
        val h = inputHandler ?: return
        val recv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
                when (intent.action) {
                    android.content.Intent.ACTION_SCREEN_ON,
                    android.content.Intent.ACTION_USER_PRESENT -> {
                        if (released) return
                        HLog.i(TAG, "screen on/user-present ⇒ resume deaf-check")
                        h.removeCallbacks(deafCheckRunnable)
                        deafPausedLogged = false
                        h.post(deafCheckRunnable)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ctx.registerReceiver(recv, filter, null, h, android.content.Context.RECEIVER_NOT_EXPORTED)
            screenReceiver = recv
            screenReceiverCtx = ctx
            HLog.i(TAG, "screen receiver registered")
        }.onFailure { HLog.w(TAG, "screen receiver register failed: ${it.message}") }
    }

    /**
     * 是否允许在"观察窗到点"时重建：息屏或锁屏时返回 false（没有可用的触摸源，
     * 重建只会刷日志/通道）。读不到 Power/Keyguard 时按 true（保持旧行为）。
     */
    private fun canRebuildDeafMonitor(): Boolean {
        val ctx = appContext ?: return true
        val interactive = runCatching {
            ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
        }.getOrDefault(true)
        if (!interactive) return false
        val locked = runCatching {
            ctx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked ?: false
        }.getOrDefault(false)
        return !locked
    }

    /** 建发送线程（只在 attemptStart 里调；幂等）。 */
    @Synchronized
    private fun ensureSendThread() {
        if (sendThread?.isAlive == true) return
        val t = android.os.HandlerThread("hsb-spy-send").apply { start() }
        sendThread = t
        sendHandler = android.os.Handler(t.looper)
    }

    /** 无消费方：在输入线程上释放 monitor，并顺手关掉仅它需要的发送线程。 */
    private fun disposeForIdle() {
        if (!started) return
        inputHandler?.removeCallbacksAndMessages(null)
        disposeSpy()
        started = false
        armed = false
        buildInFlight = false
        deafPausedLogged = false
        disposeSender()
        HLog.i(TAG, "spy disposed: no consumer")
    }

    private fun attemptStart() {
        if (released || started || gaveUp) return
        // 排队期间消费方可能又被关掉（2s 轮询先看到 need=true，设置页随后关闭功能）。
        val need = SpyInputPolicy.needed(
            moduleEnabled(),
            Os4Marker.romIsOs4(),
            cornerSwipeEnabled(),
            cornerFallbackActive(),
            blankTapActive(),
        )
        if (!need) {
            buildInFlight = false
            return
        }
        attempts++
        buildDown = 0L
        // 2026-11-13：多次重建后降噪——api dump / 门控快照 / 2a 快照只在头 2 轮或 verbose
        // 下打；spy built 每 4 轮留一条（前 2 轮仍全打）。避免空闲自愈把 1000 条环刷光。
        val quiet = deafRebuilds > 2
        runCatching {
            ensureSendThread()
            ensurePilferReceiver()
            // 2026-10-18 OS3 真机实锤：dispose 后**同名**重建的 monitor 收不到任何事件
            // （dispatcher 侧同名重注册不重新挂接；OS4 无此问题）⇒ 聋循环永远聋。
            // 改为每次 build 用唯一名，绕开重注册冲突。
            val channel = openSpyChannel(appContext, "hypersidebar-spy-$attempts", quiet)
                ?: error("no usable monitorInput candidate")
            channelRef = channel
            val h = inputHandler ?: error("no input handler")
            receiverRef = runCatching {
                object : InputEventReceiver(
                    channel,
                    if (receiverOnMain) Looper.getMainLooper() else h.looper
                ) {
                    override fun onInputEvent(event: InputEvent) {
                        try {
                            if (event is MotionEvent) {
                                // 尺寸逐事件现读（构建时捕获在旋转后全部失效——1005 16:41 导出
                                // 实锤：横屏 x=2306 用竖屏 w=1080 判带恒拒 ⇒ 横屏无法唤出）
                                val dm = android.content.res.Resources.getSystem().displayMetrics
                                onMotion(event, dm.widthPixels, dm.heightPixels)
                            }
                        } finally {
                            finishInputEvent(event, false)
                        }
                    }
                }
            }.getOrElse { e ->
                if (!receiverOnMain) {
                    receiverOnMain = true
                    HLog.w(TAG, "receiver on input looper failed (${e.message}) ⇒ fallback main looper")
                }
                throw e
            }
            started = true
            buildInFlight = false
            deafPausedLogged = false
            val dm0 = android.content.res.Resources.getSystem().displayMetrics
            if (!quiet || HLog.verboseEnabled || deafRebuilds % 4 == 0) {
                HLog.i(TAG, "spy built: display ${dm0.widthPixels}x${dm0.heightPixels} (build #$attempts" +
                    "${if (deafRebuilds > 0) ", deaf rebuild #$deafRebuilds" else ""}" +
                    ", receiver thread=${if (receiverOnMain) "main (fallback)" else Thread.currentThread().name})")
            }
            if (!quiet || HLog.verboseEnabled) {
                // 门控快照（1007 导出 162021 教训：角落 DOWN 被门静默拒=导出无法判读是哪个门关了）。
                // sys prefs=启动快照，本行即本进程全部转发决策的依据
                HLog.i(TAG, "forward gates: cornerSwipe=${cornerSwipeEnabled()} " +
                    "corner=${com.lsp.hypersidebar.hook.GestureThresholds.CORNER_BAND_DP}dp/" +
                    "${(com.lsp.hypersidebar.hook.GestureThresholds.CORNER_WIDTH_RATIO * 100).toInt()}% " +
                    "band=${bandWidthDp()}dp/${bandHeightPercent()}%+${bandPositionPercent()}%")
                // 2a 判据快照（2026-11-12）：OS4 上用 romIsOs4 代理；无需做角手势即可确认分档生效。
                val prop = runCatching { SysProps.get(PropNames.CORNER_SYSJUDGE).trim() }.getOrDefault("")
                HLog.i(TAG, "2a judge: os4=$os4Host " +
                    "policy=${if (os4Host) "decideSysJudge" else "fallback"} sysjudgeProp=$prop")
            }
            // 聋 monitor 观察窗：窗口内零 DOWN ⇒ dispose 重建（时序根因的自愈收口）
            h.removeCallbacks(deafCheckRunnable)
            h.postDelayed(deafCheckRunnable, DEAF_CHECK_WINDOW_MS)
        }.onFailure {
            if (attempts >= MAX_ATTEMPTS) {
                gaveUp = true
                buildInFlight = false
                HLog.w(TAG, "spy gave up after $attempts attempts (inert): ${it.message}")
            } else {
                HLog.i(TAG, "spy attempt #$attempts failed: ${it.message} — retry in ${RETRY_MS}ms")
                inputHandler?.postDelayed({ attemptStart() }, RETRY_MS)
            }
        }
    }

    /**
     * 聋 monitor 自愈检查（1005 双时机探针定案的收口实现）：build 成功 ≠ 投递链接通
     * （早建 monitor 注册进 dispatcher 却零事件）。观察窗内零 DOWN ⇒ dispose 全套重建；
     * 有事件 ⇒ armed（此后不再检查——spy 无状态，接通即稳定）。
     *
     * 2026-10-18：**删除"12 轮后永久放弃"**——空闲 ≠ 聋：宿主重启（夜里 OTA/系统界面崩溃）
     * 后 3 分钟内零触摸就把 spy 永久打死，次日全功能静默失效（OS3 真机实测复现：
     * `spy deaf after 13 rebuilds — giving up (inert)`）。改为永远重建；超过原封顶轮次后
     * 日志降为每 4 轮一条（≈1/min），避免聋行把 1000 条环形缓冲刷光。
     *
     * 2026-11-13：息屏/锁屏时没有可用触摸源，**不重建**（只 60s 复查一次）；屏幕点亮/
     * 解锁由 screen receiver 立即 post 本检查。避免锁屏过夜每 15s 换 channel/刷日志。
     */
    private fun deafCheck() {
        if (released || !started || gaveUp || armed) return
        if (buildDown > 0) {
            armed = true
            HLog.i(TAG, "spy armed: events flowing (down=$totalDown buildDown=$buildDown)")
            return
        }
        // 2026-11-13：息屏/锁屏期间没有可用触摸源，重建只会刷日志 + 换 channel。
        // 暂停自愈，低频复查；屏幕点亮/解锁由 screen receiver 立即 post 本检查。
        if (!canRebuildDeafMonitor()) {
            if (!deafPausedLogged) {
                deafPausedLogged = true
                HLog.i(TAG, "spy deaf-check paused: screen off/locked (no rebuild)")
            }
            inputHandler?.removeCallbacks(deafCheckRunnable)
            inputHandler?.postDelayed(deafCheckRunnable, DEAF_CHECK_SCREEN_OFF_MS)
            return
        }
        deafPausedLogged = false
        deafRebuilds++
        if (deafRebuilds > MAX_DEAF_REBUILDS) {
            if (deafRebuilds % 4 == 0) {
                HLog.i(TAG, "spy deaf (idle, not giving up) rebuild #$deafRebuilds")
            }
        } else {
            HLog.i(TAG, "spy deaf after ${DEAF_CHECK_WINDOW_MS / 1000}s " +
                "(monitor built too early, rebuild #$deafRebuilds)")
        }
        disposeSpy()
        started = false
        buildInFlight = true
        attemptStart()
    }

    /** dispose 全套（重建/放弃前调用，防 monitor 泄漏——0501~0505 期间 dumpsys 已漏数个）。
     *  InputMonitor.dispose 经反射（@hide）；receiver.dispose 必须在 owner Looper（本对象
     *  = [inputHandler] 的线程）执行。与 [pilferPointersNow] 共用 [pilferLock]：2a 在输入
     *  线程、空白接管在主线程、dispose 在输入线程，碰同一个 monitor 必须串行。
     *  任一步失败只记日志继续。 */
    private fun disposeSpy() {
        synchronized(pilferLock) {
            val r = receiverRef
            if (r != null) {
                if (receiverOnMain && Looper.myLooper() !== Looper.getMainLooper()) {
                    // fallback 路径：receiver 的 owner looper 是主线程，dispose 必须在那里执行。
                    val latch = CountDownLatch(1)
                    val posted = mainHandler.post {
                        try {
                            runCatching { r.dispose() }
                                .onFailure { HLog.w(TAG, "receiver dispose failed: ${it.message}") }
                        } finally {
                            latch.countDown()
                        }
                    }
                    if (!posted || !runCatching { latch.await(1, TimeUnit.SECONDS) }.getOrDefault(false)) {
                        HLog.w(TAG, "receiver dispose on main timed out/not posted")
                    }
                } else {
                    runCatching { r.dispose() }
                        .onFailure { HLog.w(TAG, "receiver dispose failed: ${it.message}") }
                }
            }
            runCatching {
                monitorRef?.let { m -> m.javaClass.getMethod("dispose").invoke(m) }
            }.onFailure { HLog.w(TAG, "monitor dispose failed: ${it.message}") }
            receiverRef = null
            channelRef = null
            monitorRef = null
        }
    }

    /**
     * 多候选解析。首选=MiuiBackGestureHook（同 ROM 生产验证）的姿势：SystemUI context
     * 的 getSystemService(InputManager) 实例直接反射 monitorGestureInput——20:40 导出
     * 实锤 InputManagerGlobal.getInstance() 路径 invoke 成功却拿不到 channel（静默
     * continue=返回 null 或 getInputChannel null）。每步结果都落日志，再失败可精确定位。
     * InputMonitor 路径不调 setTouchableRegion（marble 无此方法，gesture monitor=全屏观察）。
     */
    private fun openSpyChannel(
        context: android.content.Context?,
        monitorName: String = "hypersidebar-spy",
        quiet: Boolean = false,
    ): android.view.InputChannel? {
        // 取证：候选类方法清单（多次盲重建时降噪；失败 W 不受 quiet 影响）
        for (cn in CANDIDATE_CLASSES) {
            runCatching {
                val c = Class.forName(cn, false, SystemUiInputSpy::class.java.classLoader)
                val lines = c.declaredMethods
                    .filter { it.name.contains("monitor") || it.name == "getInstance" }
                    .joinToString { m ->
                        m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + "):" + m.returnType.simpleName
                    }
                if (!quiet || HLog.verboseEnabled) {
                    HLog.i(TAG, "api dump $cn: ${lines.ifEmpty { "(no monitor/getInstance methods)" }}")
                }
            }.onFailure {
                if (!quiet || HLog.verboseEnabled) HLog.i(TAG, "api dump $cn: class not found")
            }
        }
        // 候选 0（首选，MiuiBackGestureHook 同款）：SystemUI context 的 InputManager 服务实例
        if (context != null) {
            runCatching {
                val im = context.getSystemService(android.hardware.input.InputManager::class.java)
                    ?: error("getSystemService(InputManager)=null")
                val m = im.javaClass.methods.firstOrNull {
                    it.name == "monitorGestureInput" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: error("InputManager.monitorGestureInput(String,int) missing")
                val r = m.invoke(im, monitorName, 0)
                if (!quiet || HLog.verboseEnabled) {
                    HLog.i(TAG, "ctx candidate: monitor=${r?.javaClass?.name}")
                }
                monitorRef = r
                return monitorToChannel(r, "ctx", quiet)
            }.onFailure { HLog.w(TAG, "ctx candidate failed: ${it.message}") }
        } else {
            if (!quiet || HLog.verboseEnabled) HLog.i(TAG, "ctx candidate skipped: no context yet")
        }
        // 兜底：InputManagerGlobal 单例路径（20:40 实锤拿不到 channel，保留观测）
        for ((cn, mn) in CANDIDATES) {
            val r = runCatching {
                val c = Class.forName(cn, false, SystemUiInputSpy::class.java.classLoader)
                val inst = c.methods.firstOrNull { it.name == "getInstance" && it.parameterTypes.isEmpty() }
                    ?.invoke(null) ?: error("getInstance() missing")
                val m = c.methods.firstOrNull {
                    it.name == mn && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: error("$mn(String,int) missing")
                val res = m.invoke(inst, monitorName, 0)
                if (!quiet || HLog.verboseEnabled) {
                    HLog.i(TAG, "global candidate $cn.$mn: result=${res?.javaClass?.name}")
                }
                monitorRef = res
                res
            }.getOrElse { failure ->
                HLog.w(TAG, "candidate $cn.$mn failed: ${failure.message}")
                continue
            }
            return monitorToChannel(r, cn.substringAfterLast('.'), quiet) ?: continue
        }
        return null
    }

    /** InputMonitor→InputChannel（带逐步日志）；InputChannel 直返。失败返回 null 不抛。
     *  toString 打印 channel/host/surface 三字段——判读"服务端到底回了什么"的钥匙：
     *  host/surface 在而 channel null=unparcel 丢字段；三者全 null=服务端短路回了个空壳。 */
    private fun monitorToChannel(
        r: Any?,
        label: String,
        quiet: Boolean = false,
    ): android.view.InputChannel? {
        if (r == null) {
            HLog.w(TAG, "[$label] monitor invoke returned null")
            return null
        }
        if (r is android.view.InputChannel) return r
        return runCatching {
            if (!quiet || HLog.verboseEnabled) HLog.i(TAG, "[$label] monitor=${r}")
            val ch = r.javaClass.getMethod("getInputChannel").invoke(r) as? android.view.InputChannel
            if (ch == null) HLog.w(TAG, "[$label] getInputChannel returned null")
            ch
        }.getOrElse {
            HLog.w(TAG, "[$label] getInputChannel failed: ${it.message}")
            null
        }
    }

    // ===== B2-1（os4-trigger-final-plan §7）：带内流转发（B2-2 抢占已于 2026-10-13 删除）=====
    // 触发带几何（半宽/y 带高/带位）：2026-10-07 曾设置项化，2026-10-13（用户 m03028）
    // 收敛为 LayoutDefaults 常量（33/50/32dp），滑条删除。密度运行时现算，对屏宽/密度漂移免疫。

    /** 宿主 prefs（sys 进程=启动快照，改动须重启 SystemUI 生效——设置页已注明）：
     *  由 [SystemUiHook] 经 [start] 注入，供底角开关/几何读取。
     *  （2026-10-13 清理）原 B 路线总开关 sysInputOwner 与其 pilfer 身份字段已删除。 */
    @Volatile private var prefs: android.content.SharedPreferences? = null

    /**
     * 转发中的手势身份（**按 downTime 归并**，2026-10-17）。
     *
     * 原为六个 `@Volatile` 单槽字段（fwdDownTime/fwdDeviceId/fwdSource/fwdEdgeFlags/
     * fwdPointerId/fwdZone）：两区同时起手时互相覆盖 ⇒ 第一条流的 MOVE/UP 因
     * `fwdDownTime != ev.downTime` 被整段丢弃（既有缺陷，见
     * docs/adaptation/os4-corner-swipe-design.md §213）。现在每手势一条 [Track]，
     * 键 = `MotionEvent.downTime`，最多同时 4 条（LRU 淘汰最旧）。
     */
    private class Track(
        val deviceId: Int,
        var source: Int,
        var edgeFlags: Int,
        var pointerId: Int,
        var zone: Int,
    )

    private val tracks = object : java.util.LinkedHashMap<Long, Track>(4, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Track>): Boolean = size > 4
    }

    /** 最近一次 DOWN 的 downTime（只用于日志；轨本身按事件的 downTime 取）。 */
    @Volatile private var lastDownTime = 0L

    // ===== 输入接管（防穿透，2026-10-17）================================================
    // 为什么必须有它：monitor 通道只观察不消费 ⇒ 空白点必然穿到背后 app。要吞掉就必须在 DOWN
    // 抢流，而抢流不可回滚 ⇒ 抬手时**必然二选一**：动作命中=消费（丢弃记录）；否则=重放
    // （把整条手势 injectInputEvent 还给系统）。只在"小窗外 ≤ TAKEOVER_RING_DP 那一圈"抢。
    /** 本手势是否已为"点空白"抢过流（独立于角区的 sysPilfered，互不干扰；主线程状态）。 */
    @Volatile private var takeoverPilfered = false

    /** 交还后转入实时转发：每个后续事件到达即注入（解决"拖动被重放成一次点击"）。 */
    @Volatile private var takeoverForwarding = false
    /** 本手势已注入的事件数（诊断；输入线程在功能中途关闭时会清零）。 */
    @Volatile private var takeoverInjected = 0
    /**
     * 注入被系统拒绝 ⇒ **临时**关掉接管 60 秒（不是永久）。
     *
     * 2026-10-17 修正：原来一失败就永久关 ✗——真机上"多试几次后穿透"就是这么来的（某次注入恰好
     * 失败 ⇒ 之后再也不抢流 ⇒ 用户看到的就是"功能失效/穿透"）。现在：连续 2 次全失败才关，
     * 且 60 秒后自动重试（期间点击照常落给系统，不会吞）。
     */
    @Volatile private var takeoverBrokenAt = 0L
    private var takeoverFailStreak = 0
    /** 接管手势的起点（判"仍是轻点"用）。 */
    private var takeoverDownX = 0f
    private var takeoverDownY = 0f
    private var takeoverDownT = 0L

    /**
     * 我们自己注入的事件指纹（2026-10-17 回声修复）。
     *
     * 为什么必须有：spy 是 **monitor**，它看得到**我们注入回去的事件** ⇒ 注入的 DOWN 会再次进入
     * `onMotion` ⇒ 又 arm ⇒ 又 pilfer ⇒ 又注入…（真机日志里同一个 `dt` 循环了 3 次）。
     * 这里记下注入过的 `(downTime,eventTime)`，`onMotion` 见到同指纹的事件直接丢弃。
     * 上限 256 条、命中即删（一次性指纹，不会误伤真正的重复触摸——真实触摸的 eventTime 不会重合）。
     * 2026-10-18：`HashSet<Long>`（装箱+装箱哈希）换 `LongSparseArray`（原生 long 键，
     * 逐事件零装箱；onMotion 是每次触摸的必经路）。
     */
    private val injectedFingerprints = android.util.LongSparseArray<Boolean>(64)

    private fun fingerprint(dt: Long, et: Long): Long = (dt shl 20) xor et

    /** 记录一个"我已注入"的指纹；超上限就清空（简单、无泄漏）。 */
    private fun rememberInjected(dt: Long, et: Long) {
        synchronized(injectedFingerprints) {
            if (injectedFingerprints.size() > 256) injectedFingerprints.clear()
            injectedFingerprints.put(fingerprint(dt, et), true)
        }
    }

    /** 注入失败时撤销预登记（否则这条真实事件会被当成回声吞掉）。 */
    private fun forgetInjected(dt: Long, et: Long) {
        synchronized(injectedFingerprints) {
            injectedFingerprints.remove(fingerprint(dt, et))
        }
    }
    /** 重放失败计数（自检导出用） */
    @Volatile var takeoverReplayFail = 0L; private set
    private val takeover = GestureTakeover()

    /** MOVE 合流窗（ms）：窗口内只留最新一帧，终态事件前冲刷。事件时间计。 */
    private const val MOVE_COALESCE_MS = 16L
    /** 合流窗内被扣下的最新 MOVE（框架回收 ev 前的副本；UP/CANCEL 前冲刷保序） */
    private var pendingMove: MotionEvent? = null
    private var lastMoveFwdEventTime = 0L

    // ===== 异步广播（2026-11-12，issue #12）============================================
    // 输入回调里绝不能 sendBroadcast（SystemUI 主线程繁忙时 monitor 无法 ack DOWN ⇒ ANR）。
    // 输入线程只入队，hsb-spy-send 线程排空；MOVE 在积压超过上限时丢弃（DOWN/UP/CANCEL 永远入队）。
    private const val MAX_PENDING_STREAMS = 32
    private class PendingStream(
        val ev: MotionEvent,
        val landscape: Boolean,
        val zone: Int,
        val sysPilfered: Boolean,
    )
    private val pendingStreams = java.util.ArrayDeque<PendingStream>()
    /** 本流入队/丢弃计数（verbose 诊断）。 */
    private var streamSent = 0
    private var streamDropped = 0

    // ===== 2a：底角判定搬进 SystemUI（与 InputMonitor 同进程）========================
    //
    // 为什么：真机实测（2026-10-14）底角斜滑触发率仅 19.5%，最大死因是
    //   ① pilfer 指令要跨进程往返（:ui → 广播 → SystemUI），而手势 10–20ms 就结束
    //      ⇒ 8 次请求 5 次 `identity mismatch cur(dt=0)`；
    //   ② 9/31 次连一个 ACTION_MOVE 都没到 `:ui`（`hist=-1`）。
    // 2a 的做法：**同一份 CornerTrigger 在 SystemUI 侧也跑一份**，喂的是到达观察副本的
    // 原始事件（含 history 采样）。命中 SHOW 时：
    //   - 直接 `monitorRef.pilferPointers()`——同进程、同线程、零往返、无身份竞速；
    //   - 同时给 :ui 的流广播打上 `sysPilfered=1`，让 :ui 别再发抢占指令（省掉 mismatch 噪声）。
    // :ui 侧的状态机**原样保留**：它是扇形的渲染/拖选驱动，也是本条路径失效时的退路。
    //
    // 开关：`debug.hsb.corner.sysjudge`（'0' = 关，回到纯 :ui 判定 + 广播 pilfer 的旧行为）。
    private val sysCorner = CornerTrigger()
    /** 本进程是否已对当前流 pilfer 过（UP/CANCEL 复位） */
    private var sysPilfered = false
    /** 2a 诊断：当前流收到/转发的 MOVE 事件数与其中的 history 采样总数 */
    private var diagMoves = 0
    private var diagFwdMoves = 0
    private var diagHist = 0

    /** 2a 总开关（默认开）：true = SystemUI 侧自行判定并直接 pilfer。
     *  读 `debug.hsb.corner.sysjudge`——隐藏 API，与本仓其它属性读取一致走反射
     *  （TurboLayout.sysPropGet 同款；失败=''=开）。 */
    /**
     * 角区"同进程抢流"（2a：spy 自己判定 + `pilferPointers()`）是否启用。
     *
     * **必须与转发方是否真的消费同门**（2026-10-17 真机实测的回归）：
     * OS2 的扇形是 **launcher 自己**（`EdgeGestureHook` 走 ROM 的 NavStubView 流）起的，
     * 而 `RelayGestureSink` 的 `b2Enabled()` 在 OS2 为 false（转发被丢弃）⇒ 一旦 spy 抢流，
     * launcher 收不到 MOVE（实测 `EdgeGesture: c#3 UP/CANCEL … shown=false`）、转发侧又
     * 无人消费 ⇒ **底角彻底不出**。
     *
     * 【2026-11-12 修正】原判据是 AIDL `com.miui.sidebar.ISidebarOverlay` 存在性，但该类
     * 只定义在 MIUISecurityCenter.apk（classes3.dex，2026-11-10 全 dex 扫描实锤），
     * **SystemUI 的类加载器解析不到** ⇒ 在本进程恒 false，三处 OS4 行为（2a 分档 /
     * FAST 确认帧 / 兜底早退）全部误走 OS2/3 分支。改用 [Os4Marker.romIsOs4()]
     * （`Build.VERSION.INCREMENTAL` 前缀 `OS4.`，与设置页/`FreeformBlankTapHook` 同源）。
     *
     * 注意这不是"底角功能本身"：8602+ 的正解仍是**不 pilfer**（native
     * `should_handle_event` 抑制保证独占），2a 分档默认仍关；见 [CornerPilferPolicy]。
     */
    private val os4Host: Boolean = Os4Marker.romIsOs4()

    /** 2a 开关 1s TTL 缓存：角区流每个 MOVE 都要过这里，反射+属性读不能逐事件做。 */
    @Volatile private var sysJudgeCacheAt = 0L
    @Volatile private var sysJudgeCacheVal = true

    /**
     * 底角兜底门（2026-11-01 用户口径定稿）：**用户显式开启 + 底栏当前已被收起**。
     *
     * 语义：不是"小白条不在用（navigation_mode != 2）"，而是"**底栏被收起**"——
     * 竖屏全屏（游戏/视频）时 ROM 会主动收起底栏，底角斜滑因此无人接收，兜底就是为
     * 这种时刻准备的；底栏在屏时**不接管**（那时 launcher/系统手势链路本来就在）。
     * 可见性来自 `InsetProbe` 的 SystemUI 视图 insets（显示级）；未知 ⇒ 不接管。
     *
     * 【2026-11-12 更正】OS4 用 [os4Host] 早退：兜底开关在 OS4 是**真正的空操作**
     * （设置页本就隐藏它；早退再兜一层，防止历史遗留 pref + 底栏隐藏误武装 2a，
     * 而 8602 起 2a pilfer 会触发 `request_redirect` 收回流 ⇒ 角滑"闪一下消失"）。
     * OS2/OS3 语义不变：用户显式开启 + 底栏收起才武装兜底，给三键/全屏用户用。
     */
    private fun cornerFallbackActive(): Boolean {
        if (os4Host) return false
        val p = prefs ?: return false
        return runCatching {
            com.lsp.hypersidebar.prefs.SettingsRepository(p).cornerSpyFallbackEnabled() &&
                com.lsp.hypersidebar.hook.InsetProbe.navBarHidden()
        }.getOrDefault(false)
    }

    /** launcher versionCode（懒解析一次；读不到=0，调用方按"旧行为"处理）。
     *  与 TurboLayout.launcherVersionCode() 镜像——两进程各自解析，勿合并。 */
    @Volatile
    private var launcherCodeCache = -1L

    private fun launcherVersionCode(): Long {
        if (launcherCodeCache >= 0L) return launcherCodeCache
        launcherCodeCache = runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
            app?.packageManager
                ?.getPackageInfo(com.lsp.hypersidebar.prefs.HostPackages.HOME, 0)
                ?.longVersionCode ?: 0L
        }.getOrDefault(0L)
        HLog.i(
            TAG,
            "launcher versionCode=$launcherCodeCache（2a 分档阈值=${CornerPilferPolicy.REVOKED_FROM}）"
        )
        return launcherCodeCache
    }

    private fun sysJudgeEnabled(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - sysJudgeCacheAt > 1_000L) {
            // OS4：走 decideSysJudge 分档（8602+ 默认关、<8602/读不到开；显式 prop 覆盖）。
            // OS2/3：只认"底角斜滑兜底"开关（读 prefs + Secure.navigation_mode，1s 一次足够）
            sysJudgeCacheVal = if (os4Host) {
                // 2a 分档与①同源（CornerPilferPolicy）：8602 起 2a 的 pilfer 同样会被
                // launcher request_redirect 收回；显式属性非 "0" 仍可排障开启。
                CornerPilferPolicy.decideSysJudge(
                    runCatching { SysProps.get(PropNames.CORNER_SYSJUDGE).trim() }.getOrDefault(""),
                    launcherVersionCode()
                )
            } else {
                cornerFallbackActive()
            }
            sysJudgeCacheAt = now
        }
        return sysJudgeCacheVal
    }

    /** 底角几何/阈值：与 OS2/3（EdgeGestureHook）同一份 GestureThresholds 常量。
     *  2026-10-16 起不再是设置项（实测这三个参数不决定角区成败：系统在自己的热区之外
     *  照样 pilfer 抢流），滑条删除，直接取常量。角度锥上沿 +75~85° 的取定见设计文档。 */
    private fun sysCornerOnDown(x: Float, y: Float, w: Int, h: Int): Boolean {
        val dm = runCatching { android.content.res.Resources.getSystem().displayMetrics }
            .getOrNull() ?: return false
        val gt = com.lsp.hypersidebar.hook.GestureThresholds
        return sysCorner.onDown(
            x, y, w.toFloat(), h.toFloat(),
            hotSpacePx = gt.CORNER_BAND_DP * dm.density,
            cornerWidth = w * gt.CORNER_WIDTH_RATIO,
            confirmPx = gt.CORNER_INWARD_MIN_DP * dm.density,
            inwardMinPx = gt.CORNER_INWARD_MIN_DP * dm.density,
            minAngleDeg = gt.CORNER_MIN_ANGLE_DEG,
            maxAngleDeg = gt.CORNER_MAX_ANGLE_DEG,
            abandonExtraDeg = gt.CORNER_ABANDON_EXTRA_DEG,
            // OS4（有竞争、Rust 侧 10-30ms 就认领）= 单帧；OS2/3 兜底路径没有竞争方，
            // 仍用多帧一致性防误触（见 CORNER_CONFIRM_FRAMES 注释）
            confirmFrames = if (os4Host) gt.CORNER_CONFIRM_FRAMES_FAST else gt.CORNER_CONFIRM_FRAMES,
        )
    }

    /** 同进程 pilfer：命中即抢，无广播往返、无身份竞速（仍失败只记日志，不影响扇形）。 */
    private fun sysPilferNow(reason: String, dt: Long = lastDownTime) {
        if (sysPilfered) return
        if (pilferPointersNow()) {
            sysPilfered = true
            HLog.i(TAG, "sys-pilfer ok ($reason) dt=$dt zone=${zoneName(tracksGet(dt)?.zone ?: -1)}")
        } else {
            HLog.w(TAG, "sys-pilfer failed ($reason)")
        }
    }

    /** zone 数值→报告可读名（PrefKeys：0=strip 侧带 / 1=corner 底角；-1=无轨） */
    private fun zoneName(zone: Int): String = when (zone) {
        com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_CORNER -> "corner"
        com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_STRIP -> "strip"
        else -> "none"
    }

    // ===== 输入接管（防穿透）辅助 =====================================================

    /** `pilferPointers` 的 Method（懒解析一次；反射是热点开销，见 2026-10-18 性能审计）。 */
    @Volatile private var pilferMethod: java.lang.reflect.Method? = null

    /**
     * 统一的同进程抢流入口（三处调用点共用；懒解析而非绑定点解析——`monitorRef` 有
     * candidate ctx 与 global 两条挂接路径，绑定任一赋值点都会漏掉另一条）。
     * `monitorRef` 为 null（dispose 后）⇒ false，与现状一致。
     */
    private fun pilferPointersNow(): Boolean = synchronized(pilferLock) {
        val m = pilferMethod ?: monitorRef?.let { r ->
            runCatching { r.javaClass.getMethod("pilferPointers") }.getOrNull()
                ?.also { pilferMethod = it }
        } ?: return@synchronized false
        runCatching { m.invoke(monitorRef) }.isSuccess
    }

    /** `injectInputEvent(InputEvent, int)` 的 Method（懒解析一次；live-forward 每事件都要用）。 */
    @Volatile private var injectMethod: java.lang.reflect.Method? = null
    @Volatile private var injectMethodFor: Class<*>? = null

    private fun injectMethodOf(im: Any): java.lang.reflect.Method? {
        if (injectMethod != null && injectMethodFor === im.javaClass) return injectMethod
        val m = runCatching {
            im.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
        }.getOrNull()
        if (m != null) {
            injectMethod = m
            injectMethodFor = im.javaClass
        }
        return m
    }


    /** 接管总开关：**设置项为准**（默认开）；另有 `debug.hsb.takeover=0` 强制关闭 + 注入连续失败时
     *  60 秒冷却（见 [takeoverBrokenAt]）。 */
    /** 接管回退闸 1s TTL 缓存（同 [sysJudgeEnabled]：DOWN 频读不划算）。 */
    @Volatile private var takeoverPropCacheAt = 0L
    @Volatile private var takeoverPropCacheVal = true

    private fun takeoverEnabled(): Boolean {
        val brokenAt = takeoverBrokenAt
        if (brokenAt != 0L) {
            if (android.os.SystemClock.uptimeMillis() - brokenAt < 60_000L) return false
            takeoverBrokenAt = 0L
            takeoverFailStreak = 0
            HLog.i(TAG, "takeover: 冷却结束，重新尝试抢流")
        }
        val p = prefs ?: return false
        if (!p.getBoolean(
                com.lsp.hypersidebar.prefs.PrefKeys.FREEFORM_BLANK_TAP_TAKEOVER,
                com.lsp.hypersidebar.prefs.LayoutDefaults.FREEFORM_BLANK_TAP_TAKEOVER
            )
        ) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now - takeoverPropCacheAt > 1_000L) {
            takeoverPropCacheVal = SysProps.get(PropNames.TAKEOVER) != "0"
            takeoverPropCacheAt = now
        }
        return takeoverPropCacheVal
    }

    /**
     * 接手判定：只要**在册小窗的几何**存在就允许抢流。
     *
     * 2026-10-17 放宽：原先还要求 "DOWN 距小窗 ≤ TAKEOVER_RING_DP 那一圈"——真机反馈"还是穿透"：
     * 用户点空白的位置往往离窗口很远 ⇒ 圈外完全不抢 ⇒ 穿透。现在有了"抬手必二选一（消费/重放）"
     * 这条硬约束，"抢了不消费导致背后 UI 失联"的血案路径已被排除（重放把没被采纳的手势还回去），
     * 所以改成**任何空白 DOWN 都抢**。回退闸仍是 `debug.hsb.takeover=0`。
     */
    private fun takeoverArmed(h: FreeformBlankTapHook, x: Float, y: Float): Boolean = runCatching {
        // 只看几何、不看状态位（理由见 FreeformBlankTapHook.takeoverRects 的注释）
        val wins = h.takeoverRects()
        if (wins.isEmpty()) {
            if (HLog.verboseEnabled) HLog.i(TAG, "takeover: no window geometry ⇒ not armed")
            return@runCatching false
        }
        // 抢流与"能否动作"同门（2026-10-17）：没有可动作的目标 ⇒ 不抢，让点击照常落到背后 app。
        // 档位敏感：close 档下 mini/挂起窗也可关 ⇒ 允许（mini 档下对 mini 窗再缩是空操作 ⇒ 不抢）。
        // 2026-10-18：档位现在**真的传进动作层**了——此前 `hasActionTarget()` 内部无条件跳过
        // mini，与这条注释相反 ⇒ 小窗收成角落图标后点空白既不动作也不接管，点击漏给桌面
        // （用户实测"空白处几乎无法正常使用"）。
        // 动画期间几何不可信（2026-10-18 真机故障：还原动画中点空白，我们的几何还是旧位置
        // ⇒ isBlank 误判"空白" ⇒ 抢流被 ROM caption 监视器回抢 ⇒ 手势被当 caption 拖拽
        // ⇒ 窗口被误最大化）。⇒ 窗口动画期间一律不抢流。
        if (h.anyWindowAnimating()) {
            if (HLog.verboseEnabled) HLog.i(TAG, "takeover: 小窗动画中 ⇒ 不抢流")
            return@runCatching false
        }
        // 挂起窗几何缺失（2026-10-25 乱跳修复的 fail-open 门）：isBlank 的 pinned 非空白
        // 排除只对有几何的窗生效，几何缺失的挂起窗图标仍会被判"空白"⇒ 点它=抢流消费=
        // 动作打到别的窗（用户实测"乱跳不展开"）。宁可本轮不接管（点击全穿透给 ROM）。
        val pinNoGeom = h.pinnedWithoutGeometry()
        if (pinNoGeom > 0) {
            if (HLog.verboseEnabled) {
                HLog.i(TAG, "takeover: $pinNoGeom 只挂起窗几何缺失 ⇒ 不抢流（fail-open）")
            }
            return@runCatching false
        }
        val mode = prefs?.getString(
            com.lsp.hypersidebar.prefs.PrefKeys.FREEFORM_BLANK_TAP_MODE,
            com.lsp.hypersidebar.prefs.LayoutDefaults.FREEFORM_BLANK_TAP_MODE_DEFAULT
        ) ?: com.lsp.hypersidebar.prefs.LayoutDefaults.FREEFORM_BLANK_TAP_MODE_DEFAULT
        if (!h.hasActionTarget(mode)) {
            if (HLog.verboseEnabled) {
                HLog.i(TAG, "takeover: 无可动作小窗（mode=$mode）⇒ 不抢流")
            }
            return@runCatching false
        }
        HLog.i(TAG, "takeover armed at (${x.toInt()},${y.toInt()}) wins=${wins.size}")
        true
    }.getOrElse {
        HLog.w(TAG, "takeoverArmed failed: ${it.message}")
        false
    }

    /** 抢流：与角区 [sysPilferNow] 同一机制、独立标志（两者可在同一手势上共存，互不干扰）。 */
    private fun takeoverPilferNow(): Boolean {
        if (takeoverPilfered) return true
        if (!pilferPointersNow()) {
            HLog.w(TAG, "takeover pilfer failed")
            return false
        }
        takeoverPilfered = true
        HLog.i(TAG, "takeover pilfer ok dt=$lastDownTime")
        return true
    }

    /**
     * 重放整条手势：把被我们抢走的触摸还给系统/背后 app。
     * 注入写法与 ROM 自己一致（`InputManager.injectInputEvent(event, 0)` = ASYNC），
     * 且 `injectInputEvent` 是 @hide ⇒ 走反射（与本文件其它隐藏 API 同款）。
     */
    private fun replayTakeover(snap: GestureTakeover.Snapshot, why: String): Boolean {
        val plan = takeover.plan(snap)
        if (plan.isEmpty()) {
            HLog.w(TAG, "takeover replay SKIPPED ($why) overflow=${snap.overflow} " +
                "— 背后 app 本手势丢失")
            return false
        }
        val n = injectSamples(snap, plan, wait = false)
        if (n == plan.size) {
            HLog.i(TAG, "takeover replay ok events=$n ($why) dt=${snap.downTime}")
            return true
        }
        takeoverReplayFail++
        HLog.w(TAG, "takeover replay FAILED after $n/${plan.size} events ($why) dt=${snap.downTime}")
        return false
    }

    /**
     * 注入一批采样；返回**成功条数**。
     * [wait]=true 用 `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH`（默认 1）——交还时缓冲必须保序；
     * [wait]=false 用 ASYNC（默认 0，与 ROM 自己的写法一致）。
     */
    private fun injectSamples(
        snap: GestureTakeover.Snapshot,
        samples: List<GestureTakeover.Sample>,
        wait: Boolean,
    ): Int {
        val ctx = appContext ?: return 0
        val im = runCatching {
            ctx.getSystemService(android.hardware.input.InputManager::class.java)
        }.getOrNull() ?: run {
            takeoverReplayFail++
            HLog.w(TAG, "inject failed: no InputManager")
            return 0
        }
        val inject = injectMethodOf(im) ?: run {
            takeoverReplayFail++
            HLog.w(TAG, "inject failed: injectInputEvent missing")
            return 0
        }
        val mode = if (wait) 1 else 0
        var n = 0
        for (s in samples) {
            val e = MotionEvent.obtain(snap.downTime, s.eventTime, s.action, s.x, s.y, s.metaState)
            e.source = s.source
            // 2026-11-12：接收器已移到 hsb-spy-input 线程，注入回显可能先于 invoke 返回
            // 到达 monitor ⇒ 必须先登记指纹，失败再撤销（否则回声成环，见字段注释）。
            rememberInjected(snap.downTime, s.eventTime)
            val r = runCatching { inject.invoke(im, e, mode) as? Boolean }
            e.recycle()
            if (r.isFailure || r.getOrNull() != true) {
                forgetInjected(snap.downTime, s.eventTime)
                break
            }
            n++
        }
        return n
    }

    /** 仍是"轻点候选"？规则在 `GestureTakeover.stillTapCandidate`（纯核、有单测）。 */
    private fun takeoverStillTap(ev: MotionEvent): Boolean {
        if (takeoverDownT == 0L) return false
        val gt = com.lsp.hypersidebar.hook.GestureThresholds
        val dm = android.content.res.Resources.getSystem().displayMetrics
        return takeover.stillTapCandidate(
            pointerCount = ev.pointerCount,
            dx = ev.x - takeoverDownX,
            dy = ev.y - takeoverDownY,
            durMs = ev.eventTime - takeoverDownT,
            slopPx = gt.BLANK_TAP_SLOP_DP * dm.density,
            maxMs = gt.BLANK_TAP_MAX_MS,
        )
    }

    /**
     * 交还：这条手势不再可能是"轻点"（拖动/多指/超时长）⇒ 把已缓冲的事件**按序**注入，
     * 随后转入**实时转发**（后续事件到达即注入）。
     *
     * 为什么不能等 UP 再整条重放（真机反馈"拖动被吞/无法正常触发"）：重放发生在手指已抬起之后，
     * 一次拖动被还原成"起点的一次点击 + 一串滞后事件"，桌面/应用侧的滚动、fling 判定全部失真。
     */
    private fun releaseTakeover() {
        val snap = takeover.end() ?: run {
            takeover.clear()
            takeoverPilfered = false
            return
        }
        val plan = takeover.plan(snap)
        // 2026-11-12：WAIT_FOR_FINISH 会在调用线程上等输入分发完成；这段现在跑在主线程，
        // 正是要消除的阻塞。顺序由同一 InputManager 的调用序列保证（ASYNC 仍按序入队）。
        val n = injectSamples(snap, plan, wait = false)
        takeover.clear()
        if (n == 0 && plan.isNotEmpty()) {
            takeoverFailStreak++
            takeoverForwarding = false
            takeoverPilfered = false
            HLog.w(
                TAG,
                "takeover inject 全失败（streak=$takeoverFailStreak）⇒ 本手势丢失，点击先落给系统"
            )
            if (takeoverFailStreak >= 2) {
                takeoverBrokenAt = android.os.SystemClock.uptimeMillis()
                HLog.w(TAG, "takeover: 连续注入失败 ⇒ 冷却 60s（之后自动重试）")
            }
            return
        }
        takeoverFailStreak = 0
        takeoverForwarding = true
        takeoverInjected = n
        HLog.i(
            TAG,
            "takeover live-forward start buffered=$n/${plan.size} dt=${snap.downTime}" +
                " — 本手势交还系统，后续事件实时转发"
        )
    }

    /** 实时转发一个事件（原样、ASYNC 注入）。 */
    private fun injectLive(ev: MotionEvent) {
        val ctx = appContext ?: return
        runCatching {
            val im = ctx.getSystemService(android.hardware.input.InputManager::class.java)
                ?: return
            val m = injectMethodOf(im) ?: return
            val copy = MotionEvent.obtain(ev)
            copy.source = ev.source
            // 同上：先登记再注入，失败撤销（回显可能先到 hsb-spy-input 线程）。
            rememberInjected(ev.downTime, ev.eventTime)
            val ok = m.invoke(im, copy, 0) as? Boolean == true
            copy.recycle()
            if (ok) {
                takeoverInjected++
            } else {
                forgetInjected(ev.downTime, ev.eventTime)
                HLog.w(TAG, "takeover live inject rejected (a=${ev.actionMasked})")
            }
        }.onFailure { HLog.w(TAG, "takeover live inject failed: ${it.message}") }
    }

    /** 2a 逐点喂入（含 history 采样）；返回是否命中 SHOW。 */
    private fun sysCornerOnMove(ev: MotionEvent): Boolean {
        var show = false
        for (s in ev.toGestureSamples()) {
            if (sysCorner.onMove(s.x, s.y) == CornerTrigger.Action.SHOW) { show = true; break }
        }
        return show
    }

    /** 注册抢占指令接收器（**只服务底角线**，2026-10-13）：:ui 底角锥内确认（SHOW）→
     *  五要素全匹配才 pilferPointers，系统收到 CANCEL 便不弹 recents/home。
     *  条上（竖屏触发带）的抢占已删除（用户 m03028：那是给 native 的冗余兜底，会打断
     *  native 判定流程）。Token 校验同 QS 点击接收器（0912 审查口径：RECEIVER_EXPORTED
     *  必须带令牌）。
     *
     *  ⚠ 本函数同时是 **API 102 热重载的重挂入口**（由 `SystemUiHook.rearmHostSide` 调用）：
     *  attach 钩子里的注册（`SystemUiHook.armHostSide`）与热重载重挂共用它——这就是唯一实现，
     *  幂等靠下面的 `pilferReceiver != null` 守卫。注意 spy 的 `start()` 不在这里，
     *  它由 `XposedInit.onHotReloaded` 单独重起（见 [releaseForReload] 注释）。 */
    fun registerPilferReceiver(ctx: android.content.Context, modulePrefs: android.content.SharedPreferences) {
        if (prefs == null) prefs = modulePrefs
        // 幂等守卫（热重载语义）：已注册就直接返回——旧代同一代内重复调用，或新代与
        // 旧代注册重叠时，**不允许**出现第二个接收器（pilfer 会执行两遍）。
        if (pilferReceiver != null) return
        pilferCtx = ctx
        // 2026-11-12：注册也挪到输入线程（receiver 与 tracks/pilferPointersNow 同线程，
        // 不再需要锁）。此刻线程可能还没建（attach 早于首建）⇒ 留待 attemptStart。
        val h = inputHandler
        if (h != null) h.post { ensurePilferReceiver() }
    }

    /**
     * 在输入线程注册 pilfer 接收器（幂等；只在确实需要 monitor 时调用）。
     * receiver 与 `tracks`/`pilferPointersNow` 同线程 ⇒ 正常路径无锁。
     *
     * 兜底：ROM 若拒绝 `registerReceiver(..., handler, RECEIVER_NOT_EXPORTED)` 重载，
     * 退回主线程注册（receiver 会在主线程回调）⇒ 置 [pilferReceiverOnMain]，
     * 所有 `tracks` 访问改走 synchronized 包装（见 [tracksGet]/[tracksPut]）。
     */
    private fun ensurePilferReceiver() {
        if (pilferReceiver != null) return
        val ctx = pilferCtx ?: return
        val h = inputHandler ?: return
        // 直接用传入的原始 ctx 注册：attach 时刻 applicationContext=null
        //（1005 三犯——start/sink 同款教训，全部收敛为原始 ctx）
        val recv = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
                // 全链留痕（低频，不会被 MOVE 刷掉）：token/身份/pilfer 结果都可判读
                if (!com.lsp.hypersidebar.util.RelayToken.verifyFan(
                        intent, prefs?.let { com.lsp.hypersidebar.util.RelayToken.read(it) }
                    )
                ) {
                    HLog.w(TAG, "pilfer command rejected: token mismatch")
                    return
                }
                val dt = intent.getLongExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_DOWN_TIME, 0L)
                val dev = intent.getIntExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_DEVICE_ID, -1)
                val src = intent.getIntExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_SOURCE, 0)
                val ef = intent.getIntExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_EDGE_FLAGS, 0)
                val pid = intent.getIntExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_POINTER_ID, 0)
                // 五要素全匹配才碰流（MiuiBackGestureHook accepted-DOWN 令牌模型；
                // 不匹配=完全不碰——pilfer 不可回滚）。2026-10-17：身份按 downTime 归并。
                val tr = tracksGet(dt)
                val matched = tr != null && tr.deviceId == dev &&
                    tr.source == src && tr.edgeFlags == ef && tr.pointerId == pid
                if (!matched) {
                    HLog.w(TAG, "pilfer identity mismatch: req(dt=$dt,dev=$dev,src=$src,ef=$ef,pid=$pid) " +
                        "cur(track=${if (tr == null) "gone" else "dev=${tr.deviceId},src=${tr.source},ef=${tr.edgeFlags},pid=${tr.pointerId}"})")
                    return
                }
                runCatching {
                    if (pilferPointersNow()) {
                        HLog.i(TAG, "pilfered stream dt=$dt zone=${zoneName(tracksGet(dt)?.zone ?: -1)}")
                    }
                }.onFailure { HLog.w(TAG, "pilferPointers failed: ${it.message}") }
            }
        }
        val filter = android.content.IntentFilter(com.lsp.hypersidebar.prefs.PrefKeys.SPY_PILFER_ACTION)
        val onInput = runCatching {
            // 2026-11-11：:ui → SystemUI 同为 uid 1000，内核 uid 收口（零成本）。
            // permission 必须为 null（RECEIVER_NOT_EXPORTED 与 permission 互斥）。
            ctx.registerReceiver(recv, filter, null, h, android.content.Context.RECEIVER_NOT_EXPORTED)
            true
        }.getOrElse {
            HLog.w(TAG, "pilfer register with input handler failed: ${it.message} — fallback main")
            false
        }
        if (!onInput) {
            val ok = runCatching {
                ctx.registerReceiver(recv, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                pilferReceiverOnMain = true
                true
            }.getOrElse {
                HLog.w(TAG, "pilfer register failed: ${it.message}")
                false
            }
            if (!ok) {
                pilferCtx = null
                return
            }
        }
        pilferReceiver = recv
        HLog.i(TAG, "pilfer receiver registered (底角线, thread=" +
            "${if (pilferReceiverOnMain) "main" else Thread.currentThread().name})")
    }

    /** `tracks` 访问包装：正常路径同线程无锁；兜底注册在主线程时才加锁。 */
    private fun tracksGet(dt: Long): Track? =
        if (pilferReceiverOnMain) synchronized(tracks) { tracks[dt] } else tracks[dt]

    private fun tracksContains(dt: Long): Boolean =
        if (pilferReceiverOnMain) synchronized(tracks) { tracks.containsKey(dt) }
        else tracks.containsKey(dt)

    private fun tracksPut(dt: Long, tr: Track) {
        if (pilferReceiverOnMain) synchronized(tracks) { tracks[dt] = tr } else tracks[dt] = tr
    }

    private fun tracksRemove(dt: Long) {
        if (pilferReceiverOnMain) synchronized(tracks) { tracks.remove(dt) } else tracks.remove(dt)
    }

    /**
     * API 102 热重载释放（由 [SystemUiHook.releaseForReload] 转发；幂等）。
     *
     * 为什么必须：本 object 在 SystemUI 进程持有三样**宿主进程**资源，全都不会随旧代消失：
     *  ① monitorInput spy 的 InputMonitor/InputChannel/receiver——native 侧注册在 owner looper
     *     （现 `hsb-spy-input`）上，不 dispose 就等于 dispatcher 里多留一份 spy 窗口（dumpsys
     *     input 里两份 `hypersidebar-spy`），新代再建一份就是双份观察、双份转发广播；
     *  ② pilfer / 屏幕状态广播接收器——AMS 持有的 receiver 钉住旧 classloader（见字段注释）；
     *  ③ mainHandler / inputHandler 上排队的回调（FIRST_BUILD_DELAY_MS / RETRY_MS /
     *     DEAF_CHECK_WINDOW_MS / 消费方轮询）——被排队的 Runnable 是**旧代闭包**，不清
     *     队列则旧代要等自愈循环跑完才可能被回收，且新代启动期间还会被旧代的逻辑搅一次。
     *
     * 复位启动状态（started/gaveUp/armed/attempts/deafRebuilds/totalDown…）使**同代内**再
     * `start()` 能重新走一遍延迟首建+自愈（热重载后框架不会重放 onPackageReady，
     * 新代由 `XposedInit.onHotReloaded` 显式重起 spy）。
     * ⚠ 保留 [appContext]：它只是宿主 Context 的引用（不引模块类），而新代重启时
     * `ActivityThread.currentApplication()` 有拿不到的可能——留着它等于留一条可用的 ctx 兜底。
     * 任一步失败只记日志（释放失败不等于功能失败）。
     */
    fun releaseForReload() {
        released = true
        consumerLoop = false
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
            .onFailure { HLog.w(TAG, "main queue cleanup failed: ${it.message}") }
        // monitor/receiver 必须在 owner Looper 上释放（Android 17 强制）。清理任务排到
        // hsb-spy-input，等它跑完（2s 上限）再注销接收器/关线程；超时只 W，不阻塞热重载。
        val h = inputHandler
        val t = inputThread
        if (h != null && t != null) {
            if (android.os.Looper.myLooper() === t.looper) {
                cleanupOnInput()
            } else {
                val latch = CountDownLatch(1)
                val posted = runCatching {
                    h.post {
                        try {
                            cleanupOnInput()
                        } catch (e: Throwable) {
                            HLog.w(TAG, "cleanupOnInput failed: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }
                    true
                }.getOrDefault(false)
                if (!posted) {
                    cleanupOnInput()
                } else if (!runCatching { latch.await(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
                    HLog.w(TAG, "releaseForReload: input thread cleanup timed out (2s)")
                }
            }
        } else {
            cleanupOnInput()
        }
        runCatching {
            val r = pilferReceiver
            val c = pilferCtx
            if (r != null && c != null) c.unregisterReceiver(r)
        }.onFailure { HLog.w(TAG, "pilfer unregister failed: ${it.message}") }
        pilferReceiver = null
        pilferCtx = null
        pilferReceiverOnMain = false
        // 屏幕状态 receiver 同样由 AMS 持有；必须注销（否则旧代 classloader 被钉住）。
        runCatching {
            val r = screenReceiver
            val c = screenReceiverCtx
            if (r != null && c != null) c.unregisterReceiver(r)
        }.onFailure { HLog.w(TAG, "screen receiver unregister failed: ${it.message}") }
        screenReceiver = null
        screenReceiverCtx = null
        deafPausedLogged = false
        receiverOnMain = false
        pilferMethod = null
        // 线程退出（cleanup 已摘掉自有队列；quitSafely 只收尾已投递的清理任务）
        runCatching { inputThread?.quitSafely() }
            .onFailure { HLog.w(TAG, "input thread quit failed: ${it.message}") }
        runCatching { sendThread?.quitSafely() }
            .onFailure { HLog.w(TAG, "send thread quit failed: ${it.message}") }
        inputThread = null
        inputHandler = null
        sendThread = null
        sendHandler = null
        // 启动状态复位
        started = false
        armed = false
        gaveUp = false
        attempts = 0
        deafRebuilds = 0
        buildInFlight = false
        idleLogged = false
        totalDown = 0L
        buildDown = 0L
        bottomBandDown = 0L
        edgeBandDown = 0L
        streamSent = 0
        streamDropped = 0
        // 主线程侧的手势状态（takeover 只允许主线程碰）：与输入线程清理分开做。
        runOnMainBlocking { cleanupMainState() }
        HLog.i(TAG, "releaseForReload: spy disposed, pilfer receiver released, " +
            "threads quit, startup state reset")
    }

    /** 输入线程上的资源清理（monitor/receiver/发送线程/流状态）。 */
    private fun cleanupOnInput() {
        inputHandler?.removeCallbacksAndMessages(null)
        disposeSpy()
        disposeSender()
        pendingMove?.recycle()
        pendingMove = null
        lastMoveFwdEventTime = 0L
        // 身份改按 downTime 归并（2026-10-17）：清空全部轨；接管记录一并丢（旧代不再结算）
        synchronized(tracks) { tracks.clear() }
        lastDownTime = 0L
        sysPilfered = false
        runCatching { sysCorner.reset() }
            .onFailure { HLog.w(TAG, "corner reset failed: ${it.message}") }
        diagMoves = 0
        diagFwdMoves = 0
        diagHist = 0
        streamSent = 0
        streamDropped = 0
    }

    /** 主线程状态复位（release 时在主线程执行；[released] 生效后不会再有新的手势进入）。 */
    private fun cleanupMainState() {
        takeover.clear()
        takeoverForwarding = false
        takeoverPilfered = false
        takeoverInjected = 0
        blankAtDown = null
        systemSurfaceGesture = false
        BlankTapDetector.reset()
    }

    /** 在主线程同步执行（release 可能从 hook 线程被调用）；2s 超时只 W。 */
    private fun runOnMainBlocking(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            HLog.w(TAG, "main cleanup skipped: handler rejected the runnable")
            return
        }
        if (!runCatching { latch.await(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
            HLog.w(TAG, "main state cleanup timed out (2s)")
        }
    }

    /** 触发带判定（转发前置粗筛；精判定在 ：ui 状态机）：**仅竖屏**——侧带 × 百分比
     *  y 带（2026-10-07 参考 MiuiBackGestureHook：带高/带位双百分比，默认 33/50=
     *  现役 [H/3,2H/3]；OS2/3 launcher 通道不受影响，仍用 GestureThresholds 比例）。
     *  横屏不转发：OS2/3 横屏语义=条上触发（条上 hook 路径成熟且触发面小），
     *  spy 接管横屏既无增益又把返回手势区整段灌进广播（1005 教训）。 */
    private fun inTriggerBand(x: Float, y: Float, w: Int, h: Int): Boolean {
        if (w > h) return false
        val bandPx = bandWidthDp() * android.content.res.Resources.getSystem().displayMetrics.density
        val nearSide = x <= bandPx || x >= w - bandPx
        val bandH = h * bandHeightPercent() / 100f
        val top = (h - bandH) * bandPositionPercent() / 100f
        return y >= top && y <= top + bandH && nearSide
    }

    /** 底角三角（B4 转发门，os4-trigger-final-plan §14.6）：竖屏，左右角窗
     *  （宽=屏宽×GestureThresholds.CORNER_WIDTH_RATIO）× 底部热区
     *  （高=GestureThresholds.CORNER_BAND_DP × density，OS2/3 同源同值）。
     *  2026-10-16：热区从"屏高百分比"改为 dp 绝对值——原 25dp≈3% 被反馈"不易触发"后升成
     *  百分比滑条，而实测证明它不是瓶颈，滑条删除，回到与 OS2/3 完全一致的定义。
     *  与条带在 DOWN 处天然不相交（默认条带下沿 2H/3 << 热区上沿），zone 判定无歧义。 */
    private fun inCornerTriangle(x: Float, y: Float, w: Int, h: Int): Boolean {
        if (w > h) return false
        val gt = com.lsp.hypersidebar.hook.GestureThresholds
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        val hotPx = gt.CORNER_BAND_DP * density
        if (y < h - hotPx) return false
        val cornerPx = w * gt.CORNER_WIDTH_RATIO
        return x <= cornerPx || x >= w - cornerPx
    }

    // （2026-10-13 清理，用户 m03028）触发带几何不再设置项化：直接取 LayoutDefaults 默认参数，
    // 唯一真源=LayoutDefaults.BAND_*（CornerGeomBridge 推给 native 的也是同一组值）。
    private fun bandWidthDp(): Int = com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_WIDTH_DP
    private fun bandHeightPercent(): Int = com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_HEIGHT_PERCENT
    private fun bandPositionPercent(): Int = com.lsp.hypersidebar.prefs.LayoutDefaults.BAND_POSITION_PERCENT

    /** 底角功能开关（与 OS3 EdgeGestureHook 同键；关=角落流不转发） */
    private fun cornerSwipeEnabled(): Boolean = runCatching {
        prefs?.getBoolean(
            com.lsp.hypersidebar.prefs.PrefKeys.CORNER_SWIPE_ENABLED,
            com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SWIPE_ENABLED
        ) ?: false
    }.getOrDefault(false)

    /** 带内 DOWN 起转发该流（UP/CANCEL 结束）。MOVE 合流（1007 晚用户拍板）：
     *  120Hz 逐事件广播在拖选上表现 1-2 帧滞后——16ms 窗口只留最新一帧，
     *  UP/CANCEL 前冲刷保序（:ui 侧判定/合流的回放顺序不变）。 */
    // ===== 小窗「点击空白处」判定与动作（2026-10-16）=====
    /** 本手势 DOWN 时是否算"小窗之外的空白"（UP 时再用一次，两处都空白才算）。 */
    private var blankAtDown: Boolean? = null
    /** 阈值按本机 density 注入（判定核是纯 JVM 的 BlankTapDetector，不认识 density）。 */
    private var blankTapConfigured = false
    /** 首个 DOWN 打一次开关状态（自检报告用：判"是配置没到"还是"判定没命中"）。 */
    private var blankTapArmedLogged = false
    /** 本手势 DOWN 的坐标/时刻（UP 时打一行判定诊断，与角区的 `sys-corner UP dur=…` 同款）。 */
    private var blankDownX = 0f
    private var blankDownY = 0f
    private var blankDownT = 0L
    /** 本手势 DOWN 时已被系统表面闸拦下（UP 不再判定/动作）。 */
    private var systemSurfaceGesture = false

    /**
     * 轻点判定 → 动作。**只在 SystemUI 主线程执行**（由 [blankTapDispatch] 投递）：
     * `GestureTakeover`/`BlankTapDetector`/`blankAtDown`/`takeoverDown*` 都只在这一条线程访问。
     *
     * **只读配置 + 判坐标**，动作委派给 `FreeformBlankTapHook.current`（ROM 耦合全在那边；
     * 它未装入=该 ROM 不支持或锚点 NOT_FOUND ⇒ 这里自然什么都不做，即"功能静默关闭"）。
     *
     * 为什么判定放这里而不是新挂一个 ROM 入口：本 spy 已是全局 monitor 通道（带聋
     * monitor 重建自愈），看得到全部触摸；再挂 `MulWinSwitchEventHandler.onEvent` 只多一份
     * 类名漂移面（见 docs/adaptation/freeform-blank-tap-scheme.md §4 的取舍）。
     */
    private fun maybeBlankTap(ev: MotionEvent) {
        val p = prefs ?: return
        val ctx = appContext ?: return
        val a = ev.actionMasked
        // 总开关：关闭时整条路径不跑（原实现只在 DOWN 查，UP 仍跑 isBlank = 纯浪费 + 日志噪音）。
        if (a != MotionEvent.ACTION_CANCEL && !p.getBoolean(
                com.lsp.hypersidebar.prefs.PrefKeys.FREEFORM_BLANK_TAP_ENABLED,
                com.lsp.hypersidebar.prefs.LayoutDefaults.FREEFORM_BLANK_TAP_ENABLED
            )
        ) {
            BlankTapDetector.reset()
            blankAtDown = null
            systemSurfaceGesture = false
            return
        }
        when (a) {
            MotionEvent.ACTION_DOWN -> {
                // 新鲜 DOWN = 上一条手势已结束：清掉没收到 UP/CANCEL 的残留记录，
                // 否则 MOVE 会追加进旧快照、UP 按旧 downTime 重放一条拼接手势。
                if (takeover.active) {
                    takeover.clear()
                    takeoverPilfered = false
                }
                if (!blankTapArmedLogged) {
                    blankTapArmedLogged = true
                    HLog.i(TAG, "blank-tap armed=" + p.getBoolean(
                        com.lsp.hypersidebar.prefs.PrefKeys.FREEFORM_BLANK_TAP_ENABLED,
                        com.lsp.hypersidebar.prefs.LayoutDefaults.FREEFORM_BLANK_TAP_ENABLED
                    ) + " mode=" + (p.getString(
                        com.lsp.hypersidebar.prefs.PrefKeys.FREEFORM_BLANK_TAP_MODE,
                        com.lsp.hypersidebar.prefs.LayoutDefaults.FREEFORM_BLANK_TAP_MODE_DEFAULT
                    ) ?: "-") + " action=${FreeformBlankTapHook.current != null}")
                }
                val h = FreeformBlankTapHook.current ?: run {
                    systemSurfaceGesture = false
                    return
                }
                // 系统表面闸（QS/通知栏/锁屏/bouncer，2026-11-12）：只有**明确没有**系统
                // 表面时才继续；true 与 null（状态未知）都拦——fail-closed。必须放在
                // BlankTapDetector.onDown / takeoverArmed 之前：一旦抢流，磁贴点击就被吃掉。
                val sys = h.systemSurfaceOnTop(ctx)
                if (sys != false) {
                    systemSurfaceGesture = true
                    blankAtDown = null
                    BlankTapDetector.reset()
                    return
                }
                systemSurfaceGesture = false
                if (!blankTapConfigured) {
                    val dm = android.content.res.Resources.getSystem().displayMetrics
                    BlankTapDetector.configure(
                        com.lsp.hypersidebar.hook.GestureThresholds.BLANK_TAP_SLOP_DP * dm.density,
                        com.lsp.hypersidebar.hook.GestureThresholds.BLANK_TAP_MAX_MS
                    )
                    blankTapConfigured = true
                }
                blankDownX = ev.x; blankDownY = ev.y; blankDownT = ev.eventTime
                BlankTapDetector.onDown(ev.x, ev.y, ev.eventTime, ev.pointerCount)
                blankAtDown = h.isBlank(ctx, ev)
                // ===== 防穿透：输入接管（2026-10-17）=========================================
                // monitor 通道只观察不消费 ⇒ 空白点必然穿到背后 app。要吞掉就必须在 DOWN
                // 抢流（pilfer 不可回滚），所以只在**小窗外 ≤ TAKEOVER_RING_DP 的那一圈**抢
                // ——用户点"小窗旁边那块空白"就是这一圈；圈外行为与今天完全一致（动作照旧、
                // 点击照旧穿透）。抬手时必然二选一：动作命中=消费，否则=重放（见 UP 分支）。
                // 上一轮血案的教训（抢了不消费 ⇒ 背后 UI 失联 ⇒ 整机像卡死）就靠"必二选一"
                // 与"只在圈内抢"两条约束排除。
                val dmB = android.content.res.Resources.getSystem().displayMetrics
                if (blankAtDown == true && takeoverEnabled() &&
                    !GestureThresholds.inSystemGestureBand(ev.x, ev.y, dmB.widthPixels, dmB.heightPixels, dmB.density) &&
                    takeoverArmed(h, ev.x, ev.y)
                ) {
                    if (takeover.active) {
                        // 已有接管中的手势（多指/两区同起）⇒ 本条不抢（回到穿透），别把前一条
                        // 的记录冲掉——重放需要它完整。
                        if (HLog.verboseEnabled) HLog.i(TAG, "takeover busy at DOWN — 不抢流")
                    } else if (!takeoverPilferNow()) {
                        // 2026-11-12：抢流失败就不要 begin——否则 UP 会按"已接管"重放，
                        // 给背后 app 造出重复事件（点击既没被吞也没被原样放过）。
                        HLog.w(TAG, "takeover pilfer failed ⇒ 本手势不接管（点击照常穿透）")
                    } else {
                        takeover.begin(ev.downTime, ev.deviceId, ev.source)
                        takeoverDownX = ev.x
                        takeoverDownY = ev.y
                        takeoverDownT = ev.eventTime
                        takeoverForwarding = false
                        takeoverInjected = 0
                        takeover.append(
                            ev.actionMasked, ev.eventTime, ev.x, ev.y, ev.metaState, ev.source
                        )
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val h = FreeformBlankTapHook.current
                if (h == null) {
                    settleTakeoverOnAbort("hook gone before UP")
                    BlankTapDetector.reset()
                    blankAtDown = null
                    systemSurfaceGesture = false
                    return
                }
                if (systemSurfaceGesture || h.systemSurfaceOnTop(ctx) != false) {
                    // DOWN 已被拦（或 UP 时系统表面才出现）：不动作、不接管；
                    // 若已抢流则按"未命中"还回去（不能吞掉一次系统表面上的点击）。
                    settleTakeoverOnAbort("system-surface")
                    BlankTapDetector.reset()
                    blankAtDown = null
                    systemSurfaceGesture = false
                    return
                }
                val blankUp = h.isBlank(ctx, ev)
                // tri-state 语义：DOWN 端"不知道"不否决（OS2 首个手势常还没捕到实例）；
                // UP 端必须**明确**空白才动作（拿不到数据 ⇒ 不动作，fail-closed 落这端）。
                val hit = BlankTapDetector.onUp(
                    ev.x, ev.y, ev.eventTime,
                    blankAtDown = blankAtDown ?: true,
                    blankAtUp = blankUp == true
                )
                // 诊断：一次轻点判定的全部输入（自检判"为什么没动"的第一手证据）。
                // 2026-11-12：普通点击（未命中）只在 verbose 打，避免日常点击刷光环形缓冲。
                if (hit || HLog.verboseEnabled) {
                    HLog.i(TAG, "blank-tap up dx=${"%.0f".format(ev.x - blankDownX)} " +
                        "dy=${"%.0f".format(ev.y - blankDownY)} " +
                        "dur=${ev.eventTime - blankDownT}ms blankDown=$blankAtDown blankUp=$blankUp " +
                        "hit=$hit action=${FreeformBlankTapHook.current != null}")
                }
                blankAtDown = null
                // ===== 接管结算：必二选一（消费 / 重放）====================================
                // 消费：动作命中 ⇒ 丢弃记录（这次点击不进背后 app，这就是"防穿透"）。
                // 重放：动作未命中（拖拽/多指/超 slop/窗口没了/记录溢出）⇒ 把整条手势还给系统。
                // `try/finally` 保证不会出现"抢了流却既没消费也没重放"的半截状态（上一轮血案）。
                if (takeover.active) {
                    val snap = takeover.end()
                    try {
                        if (hit) {
                            HLog.i(TAG, "takeover consume dt=${snap?.downTime} " +
                                "events=${snap?.samples?.size ?: 0} — 本手势已吞掉，不穿到背后")
                        } else if (snap != null) {
                            replayTakeover(snap, "reject")
                        }
                    } finally {
                        takeover.clear()
                        takeoverPilfered = false
                    }
                }
                if (!hit) return
                val mode = p.getString(
                    com.lsp.hypersidebar.prefs.PrefKeys.FREEFORM_BLANK_TAP_MODE,
                    com.lsp.hypersidebar.prefs.LayoutDefaults.FREEFORM_BLANK_TAP_MODE_DEFAULT
                ) ?: com.lsp.hypersidebar.prefs.LayoutDefaults.FREEFORM_BLANK_TAP_MODE_DEFAULT
                HLog.i(TAG, "blank tap hit at (${"%.0f".format(ev.x)},${"%.0f".format(ev.y)}) mode=$mode")
                runCatching { h.perform(ctx, mode) }
                    .onFailure { HLog.w(TAG, "freeform action failed: ${it.message}") }
            }
            MotionEvent.ACTION_CANCEL -> {
                BlankTapDetector.reset()
                blankAtDown = null
                systemSurfaceGesture = false
                if (takeoverForwarding) {
                    HLog.i(TAG, "takeover live-forward end dt=${ev.downTime} injected=$takeoverInjected")
                    takeoverForwarding = false
                    takeoverInjected = 0
                    takeoverPilfered = false
                }
                // 系统取消（被别的窗口/系统手势接管）：**不重放**——系统已经判过这条手势，
                // 还回去会凭空造出一次动作（比丢失更糟）。只丢记录并留痕。
                if (takeover.active) {
                    val snap = takeover.end()
                    HLog.i(TAG, "takeover drop on CANCEL dt=${snap?.downTime} " +
                        "events=${snap?.samples?.size ?: 0} — 不重放")
                    takeover.clear()
                    takeoverPilfered = false
                }
            }
        }
    }

    /**
     * 主线程：空白/接管状态机的唯一入口（输入线程只投递事件副本）。
     * 交还转发期间后续事件实时注入，且不再参与 blank-tap 判定（否则拖动末尾的 UP
     * 可能又触发动作）；接管中的事件在这里追加，并在"不再是轻点候选"时立即交还。
     */
    private fun blankTapDispatch(ev: MotionEvent) {
        try {
            if (released) return
            val a = ev.actionMasked
            // 上一条手势的终态没到（流被掐/被系统取消）：新手势 DOWN 就地清掉，
            // 否则之后每个触摸都会被注入一遍（重复触摸、整机手感错乱）。
            if (a == MotionEvent.ACTION_DOWN && takeoverForwarding) {
                HLog.w(TAG, "takeover live-forward 残留 ⇒ 新手势重置")
                takeoverForwarding = false
                takeoverInjected = 0
                takeoverPilfered = false
            }
            if (takeoverForwarding) {
                injectLive(ev)
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    HLog.i(TAG, "takeover live-forward end dt=${ev.downTime} injected=$takeoverInjected")
                    takeoverForwarding = false
                    takeoverInjected = 0
                    takeoverPilfered = false
                    blankAtDown = null
                }
                return
            }
            if (takeover.active && a != MotionEvent.ACTION_DOWN) {
                takeover.append(ev.actionMasked, ev.eventTime, ev.x, ev.y, ev.metaState, ev.source)
                for (i in 0 until ev.historySize) {
                    takeover.append(
                        MotionEvent.ACTION_MOVE, ev.getHistoricalEventTime(i),
                        ev.getHistoricalX(i), ev.getHistoricalY(i), ev.metaState, ev.source
                    )
                }
                if (!takeoverStillTap(ev)) releaseTakeover()
            }
            runCatching { maybeBlankTap(ev) }
                .onFailure { HLog.w(TAG, "blank tap path failed: ${it.message}") }
        } finally {
            ev.recycle()
        }
    }

    /** 抬手时发现系统表面在上/动作层消失：把已抢的流按原样还回去（未命中语义）。 */
    private fun settleTakeoverOnAbort(why: String) {
        if (!takeover.active) {
            takeoverPilfered = false
            return
        }
        val snap = takeover.end()
        if (snap != null) replayTakeover(snap, why)
        takeover.clear()
        takeoverPilfered = false
    }

    private fun forwardIfNeeded(ev: MotionEvent, w: Int, h: Int) {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            // 消费方预筛（2026-11-12，issue #12）：OS2/3 的 strip 流在 :ui 侧 100% 被丢弃，
            // 但原实现仍按 ~60 次/秒广播 ⇒ 主线程 monitor ack 超时。这里先按可跨进程观测的
            // 代理门（moduleEnabled && os4）拦；收端仍保留精确门（breaker/DataDead/兜底）。
            val os4 = Os4Marker.romIsOs4()
            val m = moduleEnabled()
            // zone 判定单源（§14.6）：底角三角优先（条带与三角不相交，顺序无歧义）；
            // 底角另有独立开关门控（关=角落流不转发，OS3 同键语义）
            val corner = inCornerTriangle(ev.x, ev.y, w, h)
            if (corner) {
                val cs = cornerSwipeEnabled()
                val fb = cornerFallbackActive() // 只在角区路径求值（strip 不用）
                if (!SpyInputPolicy.forwardCorner(m, os4, cs, fb)) {
                    if (HLog.verboseEnabled) {
                        HLog.i(TAG, "skip stream: corner=true os4=$os4 module=$m " +
                            "cornerSwipe=$cs fallback=$fb")
                    }
                    return
                }
            } else {
                if (!inTriggerBand(ev.x, ev.y, w, h)) return
                if (!SpyInputPolicy.forwardStrip(m, os4)) {
                    if (HLog.verboseEnabled) {
                        HLog.i(TAG, "skip stream: corner=false os4=$os4 module=$m")
                    }
                    return
                }
            }
            val zone = if (corner) {
                com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_CORNER
            } else {
                com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_STRIP
            }
            // 身份按 downTime 归并（2026-10-17）：两区同时起手不再互相覆盖
            tracksPut(
                ev.downTime,
                Track(
                    deviceId = ev.deviceId,
                    source = ev.source,
                    edgeFlags = ev.edgeFlags,
                    pointerId = ev.getPointerId(0),
                    zone = zone,
                )
            )
            lastDownTime = ev.downTime
            pendingMove?.recycle()
            pendingMove = null
            lastMoveFwdEventTime = ev.eventTime
            streamSent = 0
            streamDropped = 0
            // 2a：同进程起一份判定；DOWN 就接管（对齐 OS2/3 的 DOWN 域接管语义）
            sysPilfered = false
            diagMoves = 0; diagFwdMoves = 0; diagHist = 0
            val judge = sysJudgeEnabled()
            if (corner && judge) {
                val claimed = runCatching { sysCornerOnDown(ev.x, ev.y, w, h) }.getOrDefault(false)
                HLog.i(TAG, "sys-corner DOWN raw=(${ev.x.toInt()},${ev.y.toInt()}) claimed=$claimed " +
                    "judge=$judge")
            }
        }
        val tr = tracksGet(ev.downTime) ?: return // 非转发流的 MOVE/UP（带外起手）——静默忽略
        val zone = tr.zone
        if (a == MotionEvent.ACTION_MOVE) {
            // 逐 MOVE 只累加整数（摘要行在流结束才拼字符串——热路径零字符串分配）
            diagMoves++; diagHist += ev.historySize
            if (ev.eventTime - lastMoveFwdEventTime < MOVE_COALESCE_MS) {
                // 窗口内：只留最新（框架会在 finishInputEvent 后回收 ev，故必须 obtain 副本）
                pendingMove?.recycle()
                pendingMove = MotionEvent.obtain(ev)
                // 2a：合流扣下的这一帧也要判——角滑常在 16ms 内就完成锥形确认，
                // 不判就等于把唯一一次机会让给"等 UP 前才冲刷"的那一帧。
                if (zone == com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_CORNER &&
                    sysJudgeEnabled() && sysPilfered.not() && sysCornerOnMove(ev)
                ) {
                    sysPilferNow("coalesced", ev.downTime)
                }
                return
            }
            lastMoveFwdEventTime = ev.eventTime
            diagFwdMoves++
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            // 一流一行默认级摘要（2026-11-22 增强 2）：冻屏时刻必须留下"抢没抢/还没还"的
            // 证据。原先只有 corner 区有这行，strip 流结束零痕迹（issue #13 故障时刻零证据）。
            // dt=downTime：**跨进程关联键**（2026-11-25）。native 发布的 payload 第 2 段、
            // 以及 :ui 的 fan end 行都带同一个值 ⇒ 导出里三条链路能按同一次按下对齐，
            // 而不是靠时间戳肉眼配对（issue #13 多手势场景下曾对不上）。
            HLog.i(TAG, "gesture end a=$a zone=${zoneName(zone)} dur=${ev.eventTime - ev.downTime}ms dt=${ev.downTime} " +
                "moves=$diagMoves fwd=$diagFwdMoves hist=$diagHist " +
                "pilfered=$sysPilfered sent=$streamSent dropped=$streamDropped judge=${sysJudgeEnabled()}" +
                if (zone == com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_CORNER)
                    " claimed=${sysCorner.claimed} shown=${sysCorner.lastReject == "SHOWN"} ${sysCorner.diag()}"
                else "")
            pendingMove?.let {
                if (zone == com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_CORNER &&
                    sysJudgeEnabled() && sysPilfered.not() && sysCornerOnMove(it)
                ) {
                    sysPilferNow("flush-before-end", ev.downTime)
                }
                enqueueStream(it, w, h, zone, sysPilfered)
                it.recycle()
            }
            pendingMove = null
        }
        // 非合流的 MOVE / DOWN：2a 也要判（这一帧是即将转发给 :ui 的同一帧）
        if (zone == com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_CORNER &&
            a == MotionEvent.ACTION_MOVE && sysJudgeEnabled() && sysPilfered.not() &&
            sysCornerOnMove(ev)
        ) {
            sysPilferNow("live", ev.downTime)
        }
        enqueueStream(ev, w, h, zone, sysPilfered)
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            tracksRemove(ev.downTime)
            sysPilfered = false
            sysCorner.reset()
        }
    }

    /**
     * 输入线程：把事件副本入队，由 `hsb-spy-send` 线程发广播——输入回调里绝不 `sendBroadcast`
     * （SystemUI 主线程繁忙时 monitor 无法 ack DOWN ⇒ issue #12 的 ANR）。
     * 只有 MOVE 在积压 ≥ [MAX_PENDING_STREAMS] 时丢弃；DOWN/UP/CANCEL 永远入队。
     * 副本由 [drainStreams] 发送后 recycle。
     */
    private fun enqueueStream(ev: MotionEvent, w: Int, h: Int, zone: Int, sysPilfered: Boolean) {
        val hSend = sendHandler ?: return
        val copy = MotionEvent.obtain(ev)
        val item = PendingStream(copy, w > h, zone, sysPilfered)
        synchronized(pendingStreams) {
            if (ev.actionMasked == MotionEvent.ACTION_MOVE &&
                pendingStreams.size >= MAX_PENDING_STREAMS
            ) {
                copy.recycle()
                streamDropped++
                return
            }
            pendingStreams.addLast(item)
            streamSent++
        }
        hSend.post { drainStreams() }
    }

    /** 发送线程：按入队顺序发广播（Intent 结构与原 sendStream 完全一致）。 */
    private fun drainStreams() {
        val ctx = appContext
        val p = prefs
        while (true) {
            val item = synchronized(pendingStreams) { pendingStreams.pollFirst() } ?: break
            try {
                if (ctx == null || p == null) continue
                val intent = android.content.Intent(com.lsp.hypersidebar.prefs.PrefKeys.SPY_STREAM_ACTION)
                    .setPackage(com.lsp.hypersidebar.prefs.HostPackages.UI_HOST)
                    .putExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_EVENT, item.ev)
                    .putExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_LANDSCAPE, item.landscape)
                    .putExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_ZONE, item.zone)
                    // 2a：SystemUI 已自行 pilfer ⇒ :ui 别再发抢占指令（发了也只有 mismatch 噪声）
                    .putExtra(com.lsp.hypersidebar.prefs.PrefKeys.SPY_EXTRA_SYS_PILFERED, item.sysPilfered)
                com.lsp.hypersidebar.util.RelayToken.attach(
                    intent, com.lsp.hypersidebar.util.RelayToken.read(p)
                )
                runCatching { ctx.sendBroadcast(intent) }
                    .onFailure { HLog.w(TAG, "stream forward failed: ${it.message}") }
            } finally {
                item.ev.recycle()
            }
        }
    }

    /** 发送线程/队列释放（输入线程或热重载）：丢弃未发副本、退出线程。 */
    private fun disposeSender() {
        val h = sendHandler
        val t = sendThread
        h?.removeCallbacksAndMessages(null)
        synchronized(pendingStreams) {
            while (true) {
                val item = pendingStreams.pollFirst() ?: break
                item.ev.recycle()
            }
        }
        runCatching { t?.quitSafely() }
            .onFailure { HLog.w(TAG, "send thread quit failed: ${it.message}") }
        if (sendThread === t) {
            sendThread = null
            sendHandler = null
        }
    }

    private fun onMotion(ev: MotionEvent, w: Int, h: Int) {
        val a = ev.actionMasked
        // 回声过滤（2026-10-17）：这是我们自己注入回去的事件 ⇒ 丢弃，否则 arm/pilfer/注入 会成环。
        // ⚠ 2026-10-18 教训：`LongSparseArray.remove(long)` 返回 void（≠ HashSet.remove 的
        // boolean），`remove(k) != null` 恒真 ⇒ 每个事件都被当回声吞掉（真机全聋实锤）。
        // 必须 indexOfKey 先判存在。
        if (synchronized(injectedFingerprints) {
                val k = fingerprint(ev.downTime, ev.eventTime)
                val hit = injectedFingerprints.indexOfKey(k) >= 0
                if (hit) injectedFingerprints.remove(k)
                hit
            }
        ) return
        // 空白/接管路径整体投到主线程（2026-11-12）：receiver 已不在主线程，而
        // `GestureTakeover`/`BlankTapDetector`/`blankAtDown` 仍只由主线程碰。
        // 必须 obtain 副本——框架在 finishInputEvent 后会回收原事件。
        if (blankTapActive()) {
            val copy = MotionEvent.obtain(ev)
            if (!mainHandler.post { blankTapDispatch(copy) }) copy.recycle()
        } else if (takeoverForwarding) {
            // 功能中途关闭：没有主线程投递来收尾，残留会钉住转发（输入线程永久 early-return）。
            HLog.w(TAG, "blank tap disabled mid-gesture ⇒ reset live-forward")
            takeoverForwarding = false
            takeoverInjected = 0
            takeoverPilfered = false
        }
        // 交还转发期间不参与条上转发/计数（原语义；注入与收尾在主线程）。
        if (takeoverForwarding) return
        if (a == MotionEvent.ACTION_DOWN) {
            totalDown++
            buildDown++
            if (ev.y > h * 3f / 4f) bottomBandDown++
            if (ev.x < 40 || ev.x > w - 40 || ev.y < 40) edgeBandDown++
            // 首个事件=投递链接通（自愈循环的终止判据；deafCheck 也会兜底置位）
            if (!armed) {
                armed = true
                HLog.i(TAG, "spy armed on first event")
            }
            // 每 DOWN 一行，但只在明细开关下（全局 monitor 上"每次系统触摸一条"是本模块
            // 唯一的逐触摸日志；常开会让 1000 条环被日常点击刷光，关键判定日志随之下沉）
            if (HLog.verboseEnabled) {
                HLog.i(TAG, "down (${ev.x.toInt()},${ev.y.toInt()}) " +
                    "src=${ev.source} dev=${ev.deviceId} edge=${ev.edgeFlags} " +
                    "t=${ev.downTime}/${ev.eventTime} | totals d=$totalDown bottom=$bottomBandDown edge=$edgeBandDown")
            }
        } else if (HLog.verboseEnabled && (a == MotionEvent.ACTION_MOVE ||
                a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
        ) {
            // B2 转发模式下 MOVE 打点=纯噪音（1000 条环形缓冲 ~2s 即被刷光，pilfer 等
            // 低频关键日志全被冲掉——0505 15:30 导出教训）：仅非转发流记 MOVE
            if (a == MotionEvent.ACTION_MOVE) {
                if (!tracksContains(ev.downTime)) {
                    HLog.i(TAG, "move x=${ev.x.toInt()} y=${ev.y.toInt()} dt=${ev.downTime}")
                }
            } else {
                HLog.i(TAG, "a=$a x=${ev.x.toInt()} y=${ev.y.toInt()} dt=${ev.downTime}")
            }
        }
        // B2-1：带内流转发（关=零开销；带外流在 DOWN 处即被排除）。
        // 2026-10-17：身份按 downTime 归并 ⇒ 用轨是否存在判断"这是不是我们转发过的流"。
        if (a == MotionEvent.ACTION_DOWN || tracksContains(ev.downTime)) {
            forwardIfNeeded(ev, w, h)
        }
    }
}
