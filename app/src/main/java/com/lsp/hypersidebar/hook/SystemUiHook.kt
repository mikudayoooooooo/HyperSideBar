package com.lsp.hypersidebar.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.SystemUiHookResult
import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import java.util.concurrent.atomic.AtomicBoolean
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHooks
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.LogDumpBridge

/**
 * SystemUI 进程 hook（批次 3，2026-09-05 定稿）：QS 磁贴数据层直点桥。
 *
 * 背景（真机测试矩阵实锤）：`cmd statusbar click-tile` 仅在 QS 交互/展开态真生效
 * （fire-and-forget——QS 收起时静默丢弃且 exit=0，与调用 uid 无关）。SystemUI 源码
 * （ear/jadx-full）显示 CommandQueue 的两处 clickTile 回调（MiuiQSFragment /
 * CentralSurfacesCommandQueueCallbacks）在"使用控制中心"时早退，门禁全在回调层，
 * `QSTile.click()` 本身无任何面板状态约束。
 *
 * 方案：本进程注册令牌校验的点击接收器，从数据层按 spec 找磁贴实例直接 click(null)
 * ——绕过一切面板状态门禁，QS 收起也能触发，零可见动作。**未固定在 QS 的磁贴**
 * 经 `MiuiQSHostAdapter.createTile(spec)`（QSHost 接口方法，QS 磁贴建议同机制）现场
 * 创建实例后点击，同样不依赖固定状态。
 *
 * resultCode 协议（有序广播）：1=已点击；0=适配器未就绪/createTile 失败/异常，
 * 发送端据此回退 root 三连兜底。
 */
class SystemUiHook(private val prefs: SharedPreferences) : BaseHook() {

    override val name = "SystemUiHook"

    private val TAG = "SystemUiHook"

    /** dagger 单例，构造于 SystemUI 启动期——构造即 stash 供接收器使用 */
    @Volatile private var hostAdapter: Any? = null

    /** 已解析的适配器类（契约校验通过的候选）；供 [obtainAdapter] 惰性构造复用 */
    @Volatile private var adapterClass: Class<*>? = null

    /** P0-3：本轮 ROM 是否提供 `CustomTileExt.exemptTemporarily()`（OS2 无 ⇒ 已知降级） */
    @Volatile private var exemptionSupported: Boolean? = null

    @Volatile private var exemptionMissingLogged = false

    /**
     * QS 点击接收器 + 注册用的原始 ctx：**原先匿名注册（引用只存在 AMS 的 receiver 表里）
     * ⇒ 热重载时无处注销**——AMS 持有的 receiver 就是旧代的匿名类，旧 classloader 因此
     * 回收不了；新代再注册一份则同一次点击被两代各处理一遍（磁贴 click 非幂等）。
     */
    @Volatile private var clickReceiver: BroadcastReceiver? = null
    @Volatile private var clickCtx: Context? = null

    /**
     * 点击后的投递诊断 Handler：原先在 [scheduleClick] 里每次 new 一个，那个延迟 800ms 的
     * Runnable 是旧代闭包且**没人能撤**。收敛成一个字段，热重载时可以整体 `removeCallbacksAndMessages`。
     */
    private val clickHandler = Handler(Looper.getMainLooper())

    override fun init() {
        // 结构化锚点解析（每宿主进程一次）：本宿主建表并写日志（自检报告经三进程日志尾回收，
        // 见 SelfCheck / LogCollector）。SystemUI 本轮只建表，不做 hook 门控 ——
        // 磁贴链路的适配器契约校验属 OS2 分支（P0-1）。
        runCatching { com.lsp.hypersidebar.anchor.AnchorResolver.resolveAll(prefs) }
            .onFailure { HLog.w(TAG, "anchor resolve failed: ${it.message}") }
        HLog.i(TAG, "anchor: ${com.lsp.hypersidebar.anchor.AnchorResolver.stateLine()}")
        // IME 权威事件源：**进程启动期**就装（早于 WMShell 的 Dagger 建图 ⇒ `PerDisplay` 构造器
        // 钩子能捕到实例，冷启动拉取路才可用）。见 InsetProbe.installWmShellHooksEagerly。
        runCatching { com.lsp.hypersidebar.hook.InsetProbe.installWmShellHooksEagerly() }
            .onFailure { HLog.w(TAG, "wmshell ime eager install failed: ${it.message}") }
        hookAdapterStash()
        hookClickReceiver()
        // 小窗「点击空白处」动作层（2026-10-16）：ROM 耦合（MulWinSwitchEventHandler /
        // MiuiFreeformModeController）全在这个子 hook 里；它锚点未命中时 present=false，
        // spy 侧的判定（SystemUiInputSpy.maybeBlankTap）自然什么都不做（功能静默关闭）。
        // 实例只建一次：init() 在新代重装时会重跑（XposedInit 每次热重载重建本 hook 实例，
        // 所以这里的 null 守卫实际只防同代重复 init）。
        if (freeformTapHook == null) {
            freeformTapHook = FreeformBlankTapHook(prefs)
        }
        // 子 hook 自己装（XposedInit.initHooks 是私有的，这里照它的口径：isInit 守卫 + 打点）
        val tap = freeformTapHook
        if (tap != null && !tap.isInit) {
            runCatching {
                tap.init()
                tap.isInit = true
                HLog.i(TAG, "Hook [${tap.name}] registered OK")
            }.onFailure {
                // current 的 setter 是 private（companion 内部维护），这里不复位：
                // init() 里 prime() 全部 runCatching 吞异常，失败时 available=false，
                // spy 侧的 isBlank/perform 照样 fail-closed（不误触发动作）。
                HLog.w(TAG, "freeform blank-tap hook install failed: ${it.message}")
            }
        }
    }

    /** 小窗「点击空白处」动作层（见 init 的注释）。 */
    private var freeformTapHook: FreeformBlankTapHook? = null

    private fun hookAdapterStash() {
        // 类解析必须走宿主 classloader（EzXHelper ClassLoaderProvider）——
        // javaClass.classLoader 是模块自身的，看不到 SystemUI 类（11:22 实测 not found）
        val adapterClass = resolveAdapterClass() ?: run {
            HLog.w(TAG, "QS host adapter not found on any candidate (ROM drift?)")
            return
        }
        this.adapterClass = adapterClass
        adapterClass.declaredConstructors.toList().createAfterHooks { param ->
            hostAdapter = param.thisObjectOrNull
            HLog.i(TAG, "QS host adapter stashed: ${adapterClass.name}")
        }
    }

    /**
     * P0-1：QS 宿主适配器**候选表 + 契约校验**。
     *
     * OS3/OS4 是 `qs.pipeline.domain.adapter.MiuiQSHostAdapter`；**OS2 没有 pipeline 层**，
     * 适配器是 `qs.QSHostAdapter`（另有 `QSTileHost` 同接口）。只凭名字命中不够 ——
     * 必须过契约校验，否则会把"名字还在但接口变了"的类当适配器 stash 起来，
     * 让点击链路在更深处才失败（更难定位）。
     *
     * 契约（**只在 adapter 自身可判定的范围内**，见下方"教训"）：
     *   ① 声明方法 `createTile(String):QSTile`（点击链路必经）
     *   ② 具备**任一**磁贴枚举来源：声明 `interactor` 字段，**或**自身声明
     *      `getTiles()/getSpecs()/getTile(String)` 之一
     *
     * ⚠️ **教训（09-27 实机，本函数第一版的 bug）**：第一版契约要求
     * "`interactor` 的**类型**上存在 `getCurrentQSTiles()`/`getCurrentTilesSpecs()`"，
     * 结果把 merlin 上的 `qs.QSHostAdapter` **误拒**了：
     *
     * ```
     * [sys/SystemUiHook] QS host adapter candidate rejected by contract: com.android.systemui.qs.QSHostAdapter
     * [sys/SystemUiHook] QS host adapter candidate rejected by contract: com.android.systemui.qs.QSTileHost
     * [sys/SystemUiHook] QS host adapter not found on any candidate (ROM drift?)
     * ```
     *
     * 但离线核查证明该契约**在 merlin 上必然失败**：
     * `CurrentTilesInteractorImpl` 在 merlin 的 dex 里**声明方法数为 0**
     * （marble 上同类型有 6 个方法、含 `getCurrentTilesSpecs`）。
     * 即"interactor 类型上找枚举方法"这一条在 merlin 上**恒为假**，
     * 于是**任何**候选都过不了 —— 这不是 ROM 漂移，是**我把可判定条件写错了**。
     *
     * 修正原则：**契约只校验 adapter 自己声明的东西**，不去要求它所依赖的第三方类型
     * （那些类型的形态随 ROM 变化）具备某个具体方法。枚举能力的两档回落已在
     * [findPinnedTile] 里处理，本函数无需越权预判。
     *
     * 全不命中 ⇒ 返回 null，`SystemUiHook` 保持"磁贴通道不可用"（fail-closed，不比现状差）。
     */
    private fun resolveAdapterClass(): Class<*>? {
        val cl = ClassLoaderProvider.safeClassLoader
        for (name in ADAPTER_CANDIDATES) {
            val cls = runCatching { cl.loadClass(name) }.getOrNull() ?: continue
            val (ok, why) = QsAdapterContract.check(cls)
            if (ok) {
                HLog.i(TAG, "QS host adapter resolved: $name ($why)")
                return cls
            }
            HLog.i(TAG, "QS host adapter candidate rejected by contract: $name — $why")
        }
        return null
    }

    private fun hookClickReceiver() {
        MethodFinder.fromClass("android.app.Application")
            .filterByName("attach")
            .filterByParamTypes(Context::class.java)
            .firstOrNull()
            ?.createAfterHook {
                val ctx = it.args[0] as? Context ?: return@createAfterHook
                armHostSide(ctx, "Application.attach")
                // 总闸「框架推送」（2026-10-14）已删除：真机证明 system uid 也写不进
                // `persist.hsb.native.hook`（NativeProbe.nativeSetProp 回 rc=-1/readback=0，
                // SystemProperties.set 抛 RuntimeException），而 fail-open 下"写 1"是空操作
                // ⇒ 总闸只有模块 App 写文件 marker 这一条路（NativeGateBridge）。
                // B1 spy（os4-trigger-final-plan §6）：observe-only 输入观察副本。
                // 挂在 attach（有 context）而非 init——MiuiBackGestureHook 实锤
                // monitorGestureInput 须从 context 的 InputManager 服务实例发起。
                // ⚠ 只在**冷启动**这里首建：热重载路径不重放 attach，spy 由
                // `XposedInit.onHotReloaded` 单独重起（见 rearmHostSide 注释）——
                // 所以这行不进 [armHostSide]。
                runCatching { SystemUiInputSpy.start(ctx, prefs) }
                    .onFailure { HLog.w(TAG, "input spy start failed: ${it.message}") }
                // IME 高度探针（2026-10-17）：隐藏 API 全断 ⇒ 用 1×1 透明窗口收 WindowInsets 读
                // `Type.ime().bottom`（见 InsetProbe 注释）。只在冷启动首建，幂等。
                runCatching { InsetProbe.start(ctx) }
                    .onFailure { HLog.w(TAG, "inset probe start failed: ${it.message}") }
                // IME insets 钩子在 InsetProbe 里（installInsetsHook + WMShell 事件源，
                // 2026-10-17）。注意区分两个真实存在的坑：
                //  - **热重载**不会重新加载新 dex 类 ⇒ 新加的类在新代里日志全无，要重启设备
                //    （docs/adaptation/freeform-blank-tap-scheme.md:322 实证）；
                //  - `install -r` + 重启宿主**会**加载新类 —— "新类永不生效"是未证实的说法。
                // 所以钩子装在哪（新类 or 已加载类）不影响正确性，只影响热重载时的可观测性。
            }
    }

    /**
     * 热重载重挂（API 102；与 [releaseForReload] 严格对称）。
     *
     * 为什么需要：本进程的注册体都挂在 `Application.attach` 钩子里，而热重载**不重放 attach**
     * ⇒ 释放后若不显式重挂，QS 点击 / 配置同步 / 日志回传 / pilfer 全部到宿主重启前失效。
     *
     * 与 attach 路径**共用同一段实现**（[armHostSide]），没有第二份注册代码。
     * ⚠ 例外：spy 的 `start()` 由 `XposedInit.onHotReloaded` 单独调用（它是 object 单例、
     * 不属本 hook 的装配面），故不在这里重起，只重挂它的 pilfer 接收器。
     * ⚠ LogCollector 不在重挂面内：它是**模块 App 进程**的日志归档端（`LOG_DUMP_REPLY`
     * 定向到模块包），hook 进程从不注册它——[releaseForReload] 里的转发是防御性空操作，
     * 这里对称地不重挂（在 hook 进程注册它只会凭空多一个永远收不到东西的接收器）。
     */
    override fun rearmHostSide(ctx: Context) = armHostSide(ctx, "hot reload")

    /** 宿主侧装配的唯一实现：attach（冷启动）与热重载重挂都走这里，全部幂等。 */
    private fun armHostSide(ctx: Context, via: String) {
        // QS 点击接收器
        if (clickReceiver == null) {
            clickCtx = ctx
            runCatching {
                val recv = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        // 严格令牌校验：点击可切换任意 QS 开关（含 VPN），无令牌一律拒绝
                        val expected = RelayToken.read(prefs)
                        val got = intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN)
                        if (expected.isNullOrEmpty() || got != expected) {
                            HLog.w(TAG, "qs tile click rejected: bad token")
                            return
                        }
                        val cn = intent.getStringExtra(PrefKeys.QS_TILE_CLICK_EXTRA)
                            ?.let { ComponentName.unflattenFromString(it) } ?: return
                        val prebind = intent.getBooleanExtra(PrefKeys.QS_TILE_PREBIND_EXTRA, false)
                        if (isOrderedBroadcast) resultCode = resolveAndClick(c, cn, prebind)
                    }
                }
                ctx.registerReceiver(
                    recv,
                    IntentFilter(PrefKeys.QS_TILE_CLICK_ACTION),
                    Context.RECEIVER_EXPORTED
                )
                clickReceiver = recv
                HLog.i(TAG, "qs tile click receiver registered (via $via)")
            }.onFailure {
                HLog.w(TAG, "qs tile click receiver register failed: ${it.message}")
                clickCtx = null
            }
        }
        runCatching {
            com.lsp.hypersidebar.anchor.AnchorResolver.completeWithContext(ctx)
            HLog.i(TAG, "anchor after arm($via): ${com.lsp.hypersidebar.anchor.AnchorResolver.stateLine()}")
        }.onFailure { HLog.w(TAG, "anchor complete failed: ${it.message}") }
        // 日志回传接收器（09-27 修复观测盲区）：此前只有 launcher/:ui 注册了
        // LogDumpBridge，**SystemUI 进程的日志永远收不到** —— 自检报告里磁贴通道
        // 等于全盲（27 日报告：[ui] 段是 securitycenter:ui，SystemUiHook 的日志
        // 一行都没有，无法判断 hook 是否加载、适配器是否解析成功、点击走到哪一档）。
        // 本进程不注册 CircuitBreaker/StatsRecorder，只回传 HLog 缓冲。
        runCatching { LogDumpBridge.register(ctx) }
            .onFailure { HLog.w(TAG, "log dump register failed: ${it.message}") }
        // ConfigSync 活同步（1007 教训）：本进程此前只有"启动快照"——装包+连续
        // 重启的时序里快照可能读到陈旧门控值（162021 导出：cornerSwipe
        // 服务端真值 true，spy 却整窗零转发；重启宿主自愈）。挂上 hook 侧接收器
        // 后门控值随设置页改动实时生效，快照陈旧自愈（:ui 同款机制）。
        // 热重载重挂顺带重发一次 ACTION_REQUEST（registerHookSide 内建）⇒ 立即拉一次最新配置。
        runCatching { com.lsp.hypersidebar.util.ConfigSync.registerHookSide(ctx, prefs) }
            .onFailure { HLog.w(TAG, "config sync register failed: ${it.message}") }
        // 首次安装/宿主重启后的令牌空窗（M04）：主动 bind 模块 App 拉一次全量配置，
        // RelayToken.adopt 拿到令牌；后台执行，不阻塞 SystemUI 启动。
        runCatching { com.lsp.hypersidebar.util.ConfigPullBridge.refreshIfStale(ctx) }
            .onFailure { HLog.w(TAG, "config pull warm-up failed: ${it.message}") }
        // B2-2：抢占指令接收端（:ui 底角 SHOW → 五要素匹配 → pilfer）。
        // 2026-10-13（用户 m03028）起只服务**底角线**：条上（竖屏触发带）那条是给
        // native 的冗余兜底、会打断 native 停顿判定链，已删除调用。
        runCatching { SystemUiInputSpy.registerPilferReceiver(ctx, prefs) }
            .onFailure { HLog.w(TAG, "pilfer receiver register failed: ${it.message}") }
    }

    /**
     * API 102 热重载释放（旧代退休前由 `XposedInit.onHotReloading` 调用；幂等）。
     *
     * 为什么必须：本 hook 是 SystemUI 进程里模块资源的**总装点**，它代表的四类东西全部
     * 由宿主长期持有、不会随旧代消失：
     *  ① QS 点击接收器（AMS 的 receiver 表持有 → 旧 classloader 回收不了；重复注册=一次点击
     *     被两代各点一遍）；
     *  ② [LogDumpBridge] / [ConfigSync] 的接收器（本进程注册的那一份，同上）；
     *  ③ [SystemUiInputSpy] 的 monitorInput spy（InputChannel/receiver 挂在 native 侧）——
     *     不 dispose 就与热重载后新代那份**同时存在**两份（Verification B5 的判据）；
     *  ④ 主线程上排队的点击投递诊断回调（旧代闭包）。
     *
     * 只释放"注册/资源"：不动 `hostAdapter`（宿主 dagger 单例，旧代实例随 classloader 一起
     * 退休，新代初始化时会自己 stash 一次），也不动 native（见 `XposedInit.onHotReloaded`）。
     * 任一步失败只 `HLog.w`——释放失败不该让热重载整体失败（框架仍会换代）。
     */
    override fun releaseForReload() {
        runCatching {
            val r = clickReceiver
            val c = clickCtx
            if (r != null && c != null) c.unregisterReceiver(r)
        }.onFailure { HLog.w(TAG, "click receiver unregister failed: ${it.message}") }
        clickReceiver = null
        clickCtx = null
        runCatching { clickHandler.removeCallbacksAndMessages(null) }
            .onFailure { HLog.w(TAG, "click handler cleanup failed: ${it.message}") }
        runCatching { SystemUiInputSpy.releaseForReload() }
            .onFailure { HLog.w(TAG, "spy release failed: ${it.message}") }
        // 子 hook 不注册任何宿主侧通道（无接收器），因此只需释放在新代里会失效的缓存：
        // 反射句柄缓存、present 标志、退出竞态状态（见 FreeformBlankTapHook.releaseForReload）。
        // 新代首次用到时按需重新探测，无需 rearm。
        runCatching { freeformTapHook?.releaseForReload() }
            .onFailure { HLog.w(TAG, "freeform blank-tap release failed: ${it.message}") }
        freeformTapHook = null
        runCatching { LogDumpBridge.releaseForReload() }
            .onFailure { HLog.w(TAG, "log dump release failed: ${it.message}") }
        runCatching { com.lsp.hypersidebar.util.ConfigSync.releaseForReload() }
            .onFailure { HLog.w(TAG, "config sync release failed: ${it.message}") }
        // LogCollector 注册在模块 App 进程（自检/日志页），本进程一般没注册它；入口是按
        // "模块侧自检链在同一热重载里退役"的口径一并调的，幂等、未注册即空操作。
        runCatching { com.lsp.hypersidebar.util.LogCollector.releaseForReload() }
            .onFailure { HLog.w(TAG, "log collector release failed: ${it.message}") }
        HLog.i(TAG, "releaseForReload: click/spy/log/config channels released")
    }

    /**
     * 取 QS 宿主适配器**实例**。
     *
     * 背景（09-27 报告 `delivered=false` 的候选死因之一）：`hostAdapter` 只由
     * `hookAdapterStash()` 在 **构造器被调用时**填充，而适配器是 dagger 单例
     * —— **只在 SystemUI 首次需要 QS（例如首次下拉状态栏）时才构造**。装完模块重启
     * SystemUI 后若从未下拉过状态栏就直接点磁贴，`hostAdapter` 仍是 null ⇒ 点击必失败。
     *
     * ⚠️ **不能靠"反射 new 一个适配器"兜底**（已核查 merlin/marble 的构造器）：
     * `<init>(QSTileHost, CurrentTilesInteractorImpl, Context, TileServiceRequestController$Builder,
     * CoroutineScope|Flags, DumpManager)` —— 多数参数无法从外部构造，用 null 填充只会得到
     * `interactor == null` 的**废实例**，点击走到更深处才失败，比直接返回 null 更难排查。
     *
     * 因此这里只做**快路径 + 明确可诊断的失败**：拿不到就返回 null，调用方 fail-closed
     * 返回 0 并回落 root 兜底（行为不比现状差），日志明确指出"QS 从未打开过"。
     * 真正的根治办法是**让用户在下拉一次状态栏之后再点磁贴**（构造器 hook 会立刻 stash），
     * 或改由 SystemUI 组件初始化时主动取单例 —— 前者零代码，后者需真机验证组件可达性。
     */
    private fun obtainAdapter(context: Context): Any? {
        hostAdapter?.let { return it }
        synchronized(this) {
            hostAdapter?.let { return it }
            if (adapterClass == null) adapterClass = resolveAdapterClass()
            if (adapterClass == null) {
                HLog.w(TAG, "adapter unavailable: no candidate passed the contract check (ROM drift?)")
                return null
            }
            // 到这里说明：类解析没问题，但构造器从未被调用过 ⇒ QS 自启动以来没被打开过。
            // 先走 **Dagger 按需取实例**（2026-10-17 新路径）：适配器本身就是 Dagger 懒提供的单例，
            // 而 Dagger 图在 SystemUI 启动时就完整了 ⇒ 直接向组件要它，不必等用户下拉面板。
            obtainAdapterViaDagger(context)?.let {
                hostAdapter = it
                HLog.i(TAG, "adapter obtained lazily via Dagger: ${adapterClass!!.name}")
                return it
            }
            HLog.w(
                TAG,
                "adapter not stashed: ${adapterClass!!.name} has not been constructed yet " +
                    "(QS never opened since SystemUI start?) — falling back to root"
            )
            return null
        }
    }

    /**
     * **不打开控制中心**也要拿到 QS 宿主适配器（OS2 的必走路径）。
     *
     * 依据（三代反编译实证）：
     * - `QSHostAdapter` 由 Dagger 懒提供（OS2：`DaggerReferenceGlobalRootComponent` 里 new，
     *   组件字段名 `qSHostAdapterProvider`）；它在「首次打开 QS」时才被构造 ⇒ 之前只能等用户下拉。
     * - 取用链：`SystemUIAppComponentFactoryBase.systemUIInitializer`（**public static** 实测）
     *   → `getSysUIComponent()` → 组件里名字含 `SHostAdapter` 的 Provider 字段 → `get()`。
     *   拿不到 static 时退回 `SystemUIApplication.mInitializer`（我们从 `Application.attach` 有 ctx）。
     * - 安全性：Dagger 组件在启动时已构建完成，这里只是**提前要一个它本来就会提供的单例**，
     *   不存在"提前触碰 ROM 静态单例把它污染"那类风险（对比 WMShell `MultiTaskingControllerStub` 血案）。
     * 全程 runCatching；任何一步失败都只记日志并保持旧行为（回落 root 兜底）。
     */
    private fun obtainAdapterViaDagger(context: Context): Any? = runCatching {
        val cls = adapterClass ?: return@runCatching null
        val loader = cls.classLoader
        val init = runCatching {
            Class.forName("com.android.systemui.SystemUIAppComponentFactoryBase", false, loader)
                .getField("systemUIInitializer").get(null)
        }.getOrNull() ?: runCatching {
            val app = context.applicationContext
            app.javaClass.getField("mInitializer").get(app)
        }.getOrNull() ?: return@runCatching null
        val comp = listOf("getSysUIComponent", "getRootComponent").firstNotNullOfOrNull { m ->
            runCatching { init.javaClass.getMethod(m).invoke(init) }.getOrNull()
        } ?: return@runCatching null
        // provider 字段：名字含 SHostAdapter/HostAdapter，且对象上有零参 get()（鸭子类型，免依赖 javax.inject）
        val prov = comp.javaClass.declaredFields.firstOrNull { f ->
            val n = f.name.lowercase()
            (n.contains("hostadapter") && n.contains("provider"))
        }?.let { f -> runCatching { f.isAccessible = true; f.get(comp) }.getOrNull() }
        val got = prov?.let { p ->
            runCatching { p.javaClass.getMethod("get").invoke(p) }.getOrNull()
        } ?: runCatching { comp.javaClass.getMethod("qSHostAdapter").invoke(comp) }.getOrNull()
        if (got != null && cls.isInstance(got)) got
        else {
            HLog.w(TAG, "Dagger adapter route failed (init=${init.javaClass.simpleName} comp=${comp.javaClass.simpleName} prov=${prov?.javaClass?.simpleName})")
            null
        }
    }.getOrNull()

    /** 数据层直点磁贴；返回 1=磁贴已定位（点击异步），0=未就绪/未找到/异常。
     *  纯反射（libxposed 新 API 无 XposedHelpers）。注意：jadx 反编译里的 Kotlin
     *  "属性"运行时不一定有 getter（11:36 实测 getInteractor NoSuchMethod），
     *  字段一律 getField 直读。
     *
     *  绑定+listening 双预热：TileLifecycleManager 在服务连接后的 pending 冲刷里，
     *  onClick 仅在 mListening==true 时投递，否则打 "Managed to get click on
     *  non-listening state..." 直接丢弃。框架 handleClick 的 !needListening 分支即
     *  setBindRequested + onStartListening 成对出现——这里补齐另一半（mService=
     *  TileLifecycleManager，onStartListening 置 mListening=true）。
     *  requestListeningState 在 HyperOS 实测抛 NPE（12:20 日志），弃用。
     *
     *  绑定那一半在下方「预热①」里已按方案 B 重写（见该处注释），不再无条件 unbind。 */
    private fun resolveAndClick(context: Context, cn: ComponentName, prebind: Boolean): Int {
        val adapter = obtainAdapter(context) ?: run {
            HLog.w(TAG, "clickTile: adapter unavailable (not stashed and could not obtain one)")
            return 0
        }
        return runCatching {
            val cl = adapter.javaClass.classLoader
            val interactor = readField(adapter, "interactor")
                ?: error("interactor field is null")
            val toSpec = cl.loadClass(CUSTOM_TILE_CLASS)
                .methods.firstOrNull { it.name == "toSpec" && it.parameterCount == 1 }
                ?: run {
                    HLog.w(TAG, "clickTile: toSpec method not found in $CUSTOM_TILE_CLASS")
                    return@runCatching 0
                }
            val spec = toSpec.invoke(null, cn) as? String ?: return@runCatching 0

            // P0-2：磁贴定位。**旧路径一律优先**（OS3/OS4 行为零变化），
            // 逐档回落到本 ROM 真正存在的那条（见 findPinnedTile 的 ROM 矩阵）。
            var isPinned = false
            val tile = findPinnedTile(adapter, interactor, spec)?.also { isPinned = true }
                ?: createdTiles.get(spec)
                ?: run {
                    // 未固定磁贴：现场创建（createTile 返回 null 则彻底不可触发）
                    val created = adapter.javaClass.methods
                        .firstOrNull { it.name == "createTile" && it.parameterCount == 1 }
                        ?.invoke(adapter, spec)
                        ?: run {
                            HLog.w(TAG, "clickTile: createTile returned null: $spec")
                            return@runCatching 0
                        }
                    HLog.i(TAG, "clickTile: tile created on demand: $spec")
                    createdTiles.put(spec, created)
                    created
                }
            HLog.i(TAG, "clickTile: tile located (pinned=$isPinned) spec=$spec")

            // 预热①绑定（2026-09-07 方案 B 重写，取代 09-06 的"解绑再重绑"）
            //
            // 旧实现的死因（反编译 TileServiceManager.java 实证）：
            //   setBindRequested 首行 `if (mBindRequested == bindRequested) return;`
            //   而 unbindService() 只清 mBound、不清 mBindRequested → 第一次点击之后
            //   mBindRequested 永久停在 true，之后所有 setBindRequested(true) 全是空操作，
            //   再也不会 bindService()；onClick 入队（TileLifecycleManager.queueMessage）后
            //   唯一的兜底 handleDeath() 又因为 mIsBound 被我们自己 unbind 清掉而首行 return
            //   → 点击永久排队，只有"手动拉一下状态栏"触发真实绑定才被冲刷补发。
            //   clash 之所以"稳定"，只是它每 27~30s 被系统回收一次、顺带把毒状态洗掉；
            //   quickpay 长连接常驻不回收 → 首次之后必挂（本次日志 08:45:28 clash 3s 内连点同样挂）。
            //
// 方案 B：不再无条件 unbind —— 已连接就保留连接走直连投递（最快，且保住
//   handleDeath 的重绑兜底），冻结由 :ui 侧 FanPrewarmer 预热制处理（fan 呼出时
//   kill+预 bind）；
            //   只有"服务实际没连上"时才强制一次绑定，且必须先复位 mBindRequested /
            //   mBindAllowed（否则仍会落进上面的早退分支），让 setBindRequested(true)
            //   必然走到 bindService()。
            var mgr: Any? = null
            var lifecycle: Any? = null
            var connected = false
            runCatching {
                mgr = readField(tile, "mServiceManager")
                    ?: error("mServiceManager field is null")
                lifecycle = readField(tile, "mService")
                    ?: error("mService field is null")
                // TileLifecycleManager.mBound 是 AtomicBoolean，代表真实连接是否还在
                connected = (readField(lifecycle!!, "mBound") as? AtomicBoolean)?.get() == true
                if (!connected) {
                    writeField(mgr!!, "mBindRequested", false)
                    writeField(mgr!!, "mBindAllowed", true)
                    // mBound=true 但服务其实没连上 → 一并清掉，否则走不到 bind 分支
                    if (readField(mgr!!, "mBound") == true) writeField(mgr!!, "mBound", false)
                }
                mgr!!.javaClass.methods
                    .firstOrNull { it.name == "setBindRequested" && it.parameterCount == 1 }
                    ?.invoke(mgr, true)
            }.onFailure {
                HLog.w(TAG, "prime bind failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            // 预热②listening：TileLifecycleManager.onStartListening 置 mListening=true，
            // 未连接场景服务连上后冲刷按序投递 onStartListening→onClick（handlePendingMessages
            // 里 !mListening 会打 "Managed to get click on non-listening state..." 直接丢弃）
            runCatching {
                lifecycle!!.javaClass.methods
                    .firstOrNull { it.name == "onStartListening" && it.parameterCount == 0 }
                    ?.invoke(lifecycle)
            }.onFailure {
                HLog.w(TAG, "onStartListening failed: ${it.javaClass.simpleName}: ${it.message}")
            }

            // 预热③豁免：MIUI 安全服务 exemptTemporarily（与框架 handleClick 同款动作，
            // 经 mCustomTileExt 反射）——豁免后台弹出/启动限制。冻结场景下配合 300ms
            // 延迟投递给 system_server 豁免/解冻传播留窗（SystemUI 直写 cgroup 被
            // SELinux 拒绝：23:10 EACCES 实证，故走豁免+延迟）
            //
            // P0-3：**OS2 无 `CustomTileExt` 类、tile 上也无 `mCustomTileExt` 字段**。
            // 旧实现直接 readField ⇒ 每次点击抛 NoSuchFieldException 并被 runCatching 吞掉，
            // 刷一条误导性的 "exemptTemporarily failed" 错误日志。这里改为**先探测再调用**：
            // 缺失 = 已知的能力降级（不是故障），降级为 info 并只打一次，同时把状态透出给自检。
            runCatching {
                val ext = readField(tile, "mCustomTileExt")
                if (ext == null) {
                    exemptionSupported = false
                    if (!exemptionMissingLogged) {
                        exemptionMissingLogged = true
                        HLog.i(
                            TAG,
                            "exemptTemporarily unavailable in this ROM (no CustomTileExt) " +
                                "— tile clicks under background/freeze restrictions may not fire"
                        )
                    }
                } else {
                    exemptionSupported = true
                    val m = ext.javaClass.methods
                        .firstOrNull { it.name == "exemptTemporarily" && it.parameterCount == 0 }
                    if (m == null) {
                        HLog.w(TAG, "CustomTileExt present but exemptTemporarily() missing")
                    } else {
                        m.invoke(ext)
                    }
                }
            }.onFailure {
                HLog.w(TAG, "exemptTemporarily failed: ${it.javaClass.simpleName}: ${it.message}")
            }

            // 字段诊断（21:45 对照实验：固定磁贴 handleClick 有日志且生效，created 磁贴
            // 连 handleClick 都不进——四个候选死因 state==0 早退/mCustomTileExt 缺失
            // NPE 静默/handler 卡死/实例销毁，用字段值直接判）
            val diag = runCatching {
                val state = (readField(tile, "mTile") as? android.service.quicksettings.Tile)?.state
                // OS2 的 CustomTile **没有** mCustomTileExt（三代反编译对照实证）⇒ 这里必须容错，
                // 否则整行 diag 直接失败成 "diag failed"，把其它字段的诊断价值一起埋掉。
                val ext = runCatching { readField(tile, "mCustomTileExt") != null }.getOrNull()
                val bound = mgr?.let { runCatching { readField(it, "mBound") }.getOrNull() }
                val req = mgr?.let { runCatching { readField(it, "mBindRequested") }.getOrNull() }
                val allow = mgr?.let { runCatching { readField(it, "mBindAllowed") }.getOrNull() }
                val listening = lifecycle?.let { runCatching { readField(it, "mListening") }.getOrNull() }
                "pinned=$isPinned state=$state ext=$ext connected=$connected bound=$bound req=$req allow=$allow listening=$listening"
            }.getOrNull() ?: "diag failed"
            HLog.i(TAG, "clickTile: $diag")

            if (prebind) {
                // 预热模式（2026-09-07 fan 呼出预热制）：prime（绑定+listening+豁免）已完成，
                // 不投递点击——未固定磁贴的实例已进 createdTiles 缓存，用户点击时直连命中。
                // RESULT_CLICKED 语义="预热受理"（bindService 请求已发出，AMS 拉新进程）
                HLog.i(TAG, "clickTile: prebind only, click deferred to user: $spec")
                return SystemUiHookResult.RESULT_CLICKED
            }

            if (isPinned) {
                // 固定磁贴：click(null) 实证路径（handleClick 全链路含 ext 联动）
                scheduleClick(lifecycle) {
                    runCatching {
                        tile.javaClass.methods
                            .firstOrNull { it.name == "click" && it.parameterCount == 1 }
                            ?.let { it.invoke(tile, null) }
                        HLog.i(TAG, "clickTile: clicked $spec")
                    }.onFailure { HLog.w(TAG, "click failed: ${it.message}") }
                }
                HLog.i(TAG, "clickTile: primed bind+listening, clicked $spec")
            } else {
                // 未固定磁贴（created on demand）：handleClick 不可依赖（21:45 对照实验
                // 实锤——clicked 后无 CustomTileExt 日志无动作；候选死因见上方 diag）。
                // 直接复刻框架 handleClick 尾段投递：addWindowToken（磁贴内启动权限）
                // → mService.onClick（豁免已在上方预热完成）
                scheduleClick(lifecycle) {
                    runCatching {
                        val token = readField(tile, "mToken") ?: error("mToken field is null")
                        runCatching {
                            readField(tile, "mWindowManager")?.let { wm ->
                                wm.javaClass.methods
                                    .firstOrNull { it.name == "addWindowToken" && it.parameterCount == 4 }
                                    ?.invoke(wm, token, TILE_WINDOW_TOKEN_TYPE, 0, null)
                            }
                        }.onFailure { HLog.w(TAG, "addWindowToken failed: ${it.message}") }
                        val svc = readField(tile, "mService") ?: error("mService field is null")
                        val onClick = svc.javaClass.methods
                            .firstOrNull { it.name == "onClick" && it.parameterCount == 1 }
                            ?: error("onClick method not found")
                        // onClick 是 void：invoke 成功也返回 null，elvis 若挂在整个链上
                        // 会把成功误报成 "not found"（2026-09-28 merlin 实锤）
                        onClick.invoke(svc, token)
                        HLog.i(TAG, "clickTile: direct onClick delivered $spec")
                    }.onFailure { HLog.w(TAG, "direct onClick failed: ${it.message}") }
                }
                HLog.i(TAG, "clickTile: primed bind+listening, direct onClick scheduled $spec")
            }
            SystemUiHookResult.RESULT_CLICKED
        }.getOrElse {
            HLog.w(TAG, "clickTile failed: ${it.message}")
            0
        }
    }

    /** 点击调度：（2026-09-07 更新）不再人为延迟、也不再解绑——
     *  绑定请求已由预热①同步发出，点击落在"bind 在飞"的窗口内就会被
     *  TileLifecycleManager 排队，服务连上后由 handlePendingMessages 按序冲刷投递。
     *
     *  lifecycle 用于投递后诊断：hasPendingClick()=true 说明这次点击只是入队、尚未送达
     *  （要等下一次绑定才冲刷），=false 说明已直连或已冲刷送达。
     *  OS2 的 TileLifecycleManager 没有 hasPendingClick()，退看排队消息集合
     *  （mQueuedMessages 非空=绑定链上还有未冲刷消息，含 listening，仅供诊断）。 */
    private fun scheduleClick(lifecycle: Any?, clickAction: Runnable) {
        val h = clickHandler
        h.post(clickAction)
        h.postDelayed({
            val pending = runCatching {
                val lc = lifecycle ?: return@runCatching null
                lc.javaClass.methods
                    .firstOrNull { it.name == "hasPendingClick" && it.parameterCount == 0 }
                    ?.invoke(lc) as? Boolean
                    ?: (readField(lc, "mQueuedMessages") as? Set<*>)?.isNotEmpty()
            }.getOrNull()
            HLog.i(TAG, "clickTile: post-delivery pendingClick=$pending")
        }, DELIVERY_DIAG_DELAY_MS)
    }

    /** 公有字段直读，非 public 回退 declared+accessible（反射容错统一入口） */
    private fun readField(obj: Any, name: String): Any? =
        runCatching { obj.javaClass.getField(name).get(obj) }
            .getOrElse {
                obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
            }

    // ===== P0-2：磁贴枚举（逐档回落；旧路径优先） =====

    /**
     * 找"**已固定**"磁贴的实例；返回 `null` = 未固定（或固定但拿不到实例）⇒ 调用方走
     * `createTile` 现场创建 + `addWindowToken`/`onClick` 投递路径。
     *
     * 三档来源按"旧路径优先"依次回落。**下面的 ROM 矩阵是 2026-09-26 从真机/固件实测的**
     * （`tools/qs_anchor_check.py`、`tools/merlin_tile_probe.py`）：
     *
     * | ROM（Android） | `interactor.getCurrentQSTiles()` | `interactor.getCurrentTilesSpecs()` | `adapter.getTiles()/getSpecs()/getTile()` |
     * |---|---|---|---|
     * | OS3.318 / OS4（A16/A17） | ✅ | ✅ | getTiles/getSpecs（**无 getTile**） |
     * | marble OS2.0.215（**A15**） | ❌ | ✅ | ✅ 三者齐全 |
     * | **merlin OS2.0.7.0（A14）** | ❌ | ❌ **都没有** | ✅ 三者齐全 |
     *
     * ⇒ 关键事实：**`adapter` 上的 `getTiles()/getSpecs()/getTile()` 是唯一跨全部 ROM 都存在的**，
     * 而 `interactor` 的枚举方法在 merlin（Android 14 底包的 HyperOS 2）上**一个都没有**
     * —— 只按 interactor 枚举会在 merlin 上完全失效。
     *
     * ⚠️ 只有**真的取到实例**才算 pinned：固定但尚未创建的磁贴，`getTile(spec)` 返回 null，
     * 此时不能谎报 pinned —— `createTile` 现场创建的实例走 `click(null)` 实测无效
     * （21:45 对照实验），必须落进 `addWindowToken` + `mService.onClick` 投递路径。
     */
    private fun findPinnedTile(adapter: Any, interactor: Any, spec: String): Any? {
        // ① OS3/OS4：interactor.getCurrentQSTiles() 直接给实例列表（旧路径）
        tilesOf(interactor, GET_CURRENT_QS_TILES)?.firstOrNull { tileSpecOf(it) == spec }
            ?.let { return it }
        // ② 通用：adapter.getTiles()（OS2 A14/A15 都靠这条；OS3/OS4 也有）
        tilesOf(adapter, "getTiles")?.firstOrNull { tileSpecOf(it) == spec }?.let { return it }
        // ③ spec 列表判定 + adapter.getTile(spec)
        //    spec 来源：interactor.getCurrentTilesSpecs()（OS3/OS4/marble-OS2）
        //             → adapter.getSpecs()（**merlin 唯一可用的**）
        if (spec in currentTileSpecs(adapter, interactor)) {
            pinnedTile(adapter, spec)?.let { return it }
        }
        return null
    }

    /** `getTileSpec():String`（QSTile 的 spec 标识），失败返回 null */
    private fun tileSpecOf(tile: Any?): String? = tile?.let {
        runCatching {
            it.javaClass.methods
                .firstOrNull { m -> m.name == "getTileSpec" && m.parameterCount == 0 }
                ?.invoke(it) as? String
        }.getOrNull()
    }

    /** 反射取 `name():Collection/List`，非集合或调用失败返回 null */
    private fun tilesOf(owner: Any, method: String): List<*>? = runCatching {
        owner.javaClass.methods
            .firstOrNull { it.name == method && it.parameterCount == 0 }
            ?.invoke(owner) as? Collection<*>
    }.getOrNull()?.toList()?.also {
        if (it.isNotEmpty()) HLog.i(TAG, "clickTile: $method -> ${it.size} tiles")
    }

    /**
     * 当前磁贴的 spec 字符串列表。两条来源逐档回落：
     * `interactor.getCurrentTilesSpecs()` → **`adapter.getSpecs()`**（merlin 上前者不存在）。
     * 失败返回空表 ⇒ 视作"未固定" ⇒ 走 createTile（与既有降级路径一致）。
     */
    private fun currentTileSpecs(adapter: Any, interactor: Any): List<String> {
        val fromInteractor = runCatching {
            interactor.javaClass.methods
                .firstOrNull { it.name == GET_CURRENT_TILES_SPECS && it.parameterCount == 0 }
                ?.invoke(interactor) as? List<*>
        }.getOrNull()?.filterIsInstance<String>()
        if (!fromInteractor.isNullOrEmpty()) return fromInteractor
        val fromAdapter = runCatching {
            adapter.javaClass.methods
                .firstOrNull { it.name == "getSpecs" && it.parameterCount == 0 }
                ?.invoke(adapter) as? List<*>
        }.getOrNull()?.filterIsInstance<String>().orEmpty()
        if (fromAdapter.isNotEmpty()) {
            HLog.i(TAG, "clickTile: specs via adapter.getSpecs -> ${fromAdapter.size}")
        }
        return fromAdapter
    }

    /**
     * OS2 路径：`adapter.getTile(spec):QSTile`。
     * 已固定但**尚未创建**的磁贴可能返回 null ⇒ 交由调用方的 createTile 兜底。
     */
    private fun pinnedTile(adapter: Any, spec: String): Any? = runCatching {
        adapter.javaClass.methods
            .firstOrNull { m -> m.name == "getTile" && m.parameterCount == 1 }
            ?.invoke(adapter, spec)
    }.getOrNull().also {
        if (it == null) HLog.i(TAG, "clickTile: getTile(spec) returned null, will createTile: $spec")
    }

    /** 字段直写（与 readField 同款容错），用于复位 TileServiceManager 绑定标志位 */
    private fun writeField(obj: Any, name: String, value: Any) {
        runCatching { obj.javaClass.getField(name).set(obj, value) }
            .getOrElse {
                obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(obj, value)
            }
    }

    private companion object {
        /**
         * QS 宿主适配器候选（**按优先级**，P0-1）。
         * - `qs.pipeline.domain.adapter.MiuiQSHostAdapter`：OS3 / OS4
         * - `qs.QSHostAdapter`：**OS2**（无 pipeline 层）；另有 `QSTileHost` 同接口，可作三号候选
         * 命中后一律过契约校验（见 `resolveAdapterClass`），不凭名字采信。
         */
        val ADAPTER_CANDIDATES = listOf(
            "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter",
            "com.android.systemui.qs.QSHostAdapter",
            "com.android.systemui.qs.QSTileHost",
        )

        /** 当前磁贴枚举：OS3/OS4 用前者，**OS2 只有后者**（P0-2） */
        const val GET_CURRENT_QS_TILES = "getCurrentQSTiles"
        const val GET_CURRENT_TILES_SPECS = "getCurrentTilesSpecs"

        const val CUSTOM_TILE_CLASS = "com.android.systemui.qs.external.CustomTile"

        /** 投递后诊断延迟：等 bindService→onServiceConnected 冲刷完成后再查 pendingClick */
        const val DELIVERY_DIAG_DELAY_MS = 800L

        /**
         * addWindowToken 的窗口类型（取值来自反编译 SystemUI handleClick 链路）：为磁贴
         * 授予"磁贴内启动"窗口权限。ROM 内部类型未考证到公开常量名——语义勿改，ROM
         * 升级若磁贴内启动失效优先怀疑此值漂移
         */
        const val TILE_WINDOW_TOKEN_TYPE = 2035

        /**
         * createTile 现场创建的实例按 spec 缓存复用（LruCache 内部同步，跨线程安全）。
         * 上界=用户添加的磁贴快捷方式数，再设硬上限防异常场景无界增长（每个实例持
         * Context/Handler/TileLifecycleManager，无界会随增删磁贴持续泄漏）。
         */
        const val MAX_CREATED_TILES = 32
        val createdTiles = android.util.LruCache<String, Any>(MAX_CREATED_TILES)
    }
}
