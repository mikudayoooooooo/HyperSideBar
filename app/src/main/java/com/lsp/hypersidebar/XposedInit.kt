package com.lsp.hypersidebar

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.LogDumpBridge
import com.lsp.hypersidebar.hook.BaseHook
import com.lsp.hypersidebar.hook.BackGestureSignalProbe
import com.lsp.hypersidebar.hook.EdgeGestureHook
import com.lsp.hypersidebar.hook.FreeformRelayHook
import com.lsp.hypersidebar.hook.SidebarOverlayProbe
import com.lsp.hypersidebar.hook.SystemUiHook
import com.lsp.hypersidebar.hook.TurboLayout
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefsFiles

class XposedInit : XposedModule() {

    private val TAG = "XposedInit"
    private var turboLayoutHook: TurboLayout? = null
    private var freeformRelayHook: FreeformRelayHook? = null
    private var edgeGestureHook: EdgeGestureHook? = null
    private var systemUiHook: SystemUiHook? = null
    private var sidebarOverlayProbe: SidebarOverlayProbe? = null
    private var backGestureSignalProbe: BackGestureSignalProbe? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        // 权威进程名播种（API @NonNull）：极早期 HLog 归类不再依赖任何反射链，
        // 1002 实机 [?] 段教训——全链 null 时 "?" 被缓存，整环标签报废
        HLog.setProcOverride(param.processName)
        // NativeLibLoader 抽 libdexkit.so 需要模块 APK 路径；必须在任何宿主 init 之前就位。
        runCatching { EzXposed.initModuleResources() }
            .onFailure { HLog.w(TAG, "initModuleResources failed: ${it.message}") }
        // wiki legacy 语义（b69f706 定稿）：assets/native_init 声明 + 模块自行
        // System.loadLibrary，框架在 dlopen 时拦截并回调 native_init。
        // 报告走双通道：native 内部照旧打 logcat（tag=HSBNative），同时经
        // NativeProbe.report() 回灌 HLog——logcat 整体失灵（OS4.0.0.14 实机）时
        // 自检导出仍是完整取证链，三段式：loadLibrary 结果 / 报告逐行 / 取报告失败。
        runCatching {
            System.loadLibrary("hypersidebar_native")
            HLog.i("HSBNative", "legacy loadLibrary ok")
            runCatching { com.lsp.hypersidebar.util.NativeProbe.report() }
                .onSuccess { rep ->
                    if (rep.isNullOrEmpty()) {
                        HLog.w("HSBNative", "report null/empty（JNI 符号未导出?）")
                    } else {
                        rep.split('\n').forEach { HLog.i("HSBNative", it) }
                    }
                }
                .onFailure { HLog.w("HSBNative", "report fetch failed: ${it.message}") }
        }.onFailure {
            HLog.e("HSBNative", "legacy loadLibrary FAILED: ${it.message}")
        }
        // 日志桥无条件武装（2026-10-02 晚升级：原 pkg==HOME 门控失效——home 进程
        // 的 onPackageReady 可能不触发或报异名包，条件匹配永远走不到，[launcher]
        // 段在 onModuleLoaded 已跑的情况下依旧拉不出来）。onModuleLoaded=模块在
        // 进程内的最早时刻，挂 Application.attach 全进程通吃；LogDumpBridge 幂等，
        // 各 hook 的富 provider 注册照常升级。HyperOS runtime（Rust 桌面）进程若
        // 有标准 Application 生命周期即被覆盖。
        armEarlyLogDumpBridge()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        EzXposed.initOnPackageReady(param)

        val procName = currentProcessName()
        HLog.i(TAG, "onPackageReady: pkg=${param.packageName} proc=$procName")
        val host = hostFor(procName)
        if (host == null) {
            HLog.w(TAG, "Skip package/process: pkg=${param.packageName} proc=$procName")
            return
        }
        installFor(host)
    }

    /**
     * 宿主身份。**只按进程名判**，不看 `packageName`。
     *
     * 为什么：两条调用路径的入参不同——`onPackageReady` 两者都有，而 API 102 的
     * `HotReloadedParam` **只有 processName**（它继承 ModuleLoadedParam，没有 packageName）。
     * 若把 host 判定写进 `when` 的 packageName 条件里，热重载路径就没法复用同一套装配。
     *
     * 子进程保护不丢：SystemUI 的截图等子进程进程名带后缀（`com.android.systemui:screenshot`）
     * ⇒ 不等 `HostPackages.SYSTEM_UI` ⇒ 仍然跳过（原注释的意图——子进程注册有序广播接收器会
     * 抢答 resultCode 覆盖主进程结果）。
     */
    private enum class Host { UI, HOME, SYSTEM_UI }

    private fun hostFor(procName: String): Host? = when {
        procName.endsWith(HostPackages.UI_PROCESS_SUFFIX) -> Host.UI
        procName == HostPackages.HOME -> Host.HOME
        procName == HostPackages.SYSTEM_UI -> Host.SYSTEM_UI
        else -> null
    }

    /**
     * 按宿主装 Java hooks。幂等（每个 hook 字段 `if (… == null)` 守 + `initHooks` 的
     * `isInit` 守），所以 `onPackageReady` 与 `onHotReloaded` 共用它。
     */
    private fun installFor(host: Host) {
        when (host) {
            Host.UI -> {
                // 横屏 B 路线触发端 + 竖屏小白条隐藏穿透宿主 + 执行端（fan 选中动作本进程直执行）
                // SyncedPrefs 包装（同 home 端批次 2）：总开关/横屏 dwell 等读取走同步广播
                // 缓存命中，实时性不再单靠 LSPosed push 订阅
                val prefs = com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                if (turboLayoutHook == null) {
                    turboLayoutHook = TurboLayout(prefs)
                }
                if (freeformRelayHook == null) {
                    // prefs 同时供 FreeformRelay 做跨进程广播令牌校验（批次 0 安全修复）
                    freeformRelayHook = FreeformRelayHook(prefs)
                }
                // E0/R1-J 前置探针（OS4 适配）：ISidebarOverlay 呼出链路观测，只打日志。
                // OS2/OS3 无该类=init 内自门控静默退出，三 ROM 通用。
                if (sidebarOverlayProbe == null) sidebarOverlayProbe = SidebarOverlayProbe()
                // ⚠ 这里原来还有一条「免 su 总闸写法人（system ui 直接写属性）」的调用，
                // 2026-10-14 晚随 NativeGateBridge 收敛一并删除：真机上属性两条免-su 写路
                // 都被 SELinux 拒（`__system_property_set` rc=-1 / 反射 RuntimeException），
                // 而 fail-open 下"写 1"本来就是空操作 ⇒ 总闸现在只由模块 App 写文件 marker 关断。
                initHooks(turboLayoutHook!!, freeformRelayHook!!, sidebarOverlayProbe!!)
                // （2026-10-24 的 PropSpike 矩阵探针已按拍板于 2026-10-25 删除：A 案免 root
                //  文件通道真机判活；探针四格全 OK 的判词归档在 os4-native-hold-route-plan.md §27.5/§27.6
                //  ——system_app 域 × persist.sys./sys. 可写，B 案备用路线已实证可行。）
            }
            Host.HOME -> {
                // 竖屏边缘手势通道（内滑+停顿零干扰透传；横屏触发已移交 :ui B 路线）
                if (edgeGestureHook == null) {
                    // SyncedPrefs 包装（批次 2）：配置同步广播缓存命中优先——
                    // RemotePreferences 本体是死快照且 LSPosed 框架侧还做进程级
                    // 缓存（重调 API 也是同一实例），配置实时性只能走广播通道
                    edgeGestureHook = EdgeGestureHook(
                        com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                    )
                }
                initHooks(edgeGestureHook!!)
            }
            Host.SYSTEM_UI -> {
                // 批次 3：QS 磁贴数据层直点桥（click-tile 门禁在回调层，QSTile.click 无约束）
                if (systemUiHook == null) {
                    systemUiHook = SystemUiHook(
                        com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                    )
                }
                // 探针 0（log-only，prop 门控：debug.hsb.bgprobe=1）：判"系统手势识别信号
                // 在 OS4 的 Java 侧是否可得"，决定门控落 Java 还是必须 native。
                if (backGestureSignalProbe == null) backGestureSignalProbe = BackGestureSignalProbe()
                initHooks(systemUiHook!!, backGestureSignalProbe!!)
            }
        }
    }

    /**
     * API 102 热重载 · 第一拍：**旧代**被退休前（框架要求显式返回 true 才放行）。
     *
     * 做什么：把旧代持有的模块资源全部释放（否则旧 classloader 无法回收，且新代再装一遍
     * 会得到两份 monitor / 两份接收器）。逐个 hook 走 `BaseHook.releaseForReload()`。
     *
     * 不做什么：不碰 native（见 onHotReloaded）；不写 `setSavedInstanceState`
     * ——本模块没有必须跨代继承的状态：`CircuitBreaker` / `DataDeadState` 这些单例
     * 在新代是从零开始，而它们的语义是"本进程本世代内的熔断/判死"，重置是正确行为
     * （旧代若已熔断，新代码本身就是一次可用的重新尝试）。日志 ring buffer 同理：新代从空开始，
     * 旧代内容已通过 logcat / LogDumpBridge 落过盘。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // ⚠ HotReloadingParam 只有 getExtras()/setSavedInstanceState()，**没有** getProcessName()
        // （API 102 的 HotReloadedParam 才继承 ModuleLoadedParam）⇒ 这里自己取进程名。
        HLog.i(TAG, "hot reload: onHotReloading proc=${currentProcessName()}")
        for ((n, h) in reloadTargets()) {
            runCatching { h.releaseForReload() }
                .onFailure { HLog.w(TAG, "releaseForReload [${n}] failed: ${it.message}") }
        }
        // 进程级共享单例（非 BaseHook，但同样持有跨代资源：常驻线程/线程池/overlay 窗）。
        // 全部幂等（未起过 = 空操作）；旧代自清 ⇒ 旧 classloader 才能被回收。
        // 2026-10-18 依 libxposed skill 03 的清理清单补：Java 线程 / 外部回调 / 宿主侧引用。
        for ((n, block) in listOf(
            "InsetProbe(overlay)" to { com.lsp.hypersidebar.hook.InsetProbe.releaseForReload() },
            "ConfigPullBridge(HandlerThread)" to { com.lsp.hypersidebar.util.ConfigPullBridge.releaseForReload() },
            "UnfreezeBridge(HandlerThread)" to { com.lsp.hypersidebar.util.UnfreezeBridge.releaseForReload() },
            "AppIconCache(pool)" to { com.lsp.hypersidebar.util.AppIconCache.releaseForReload() },
            "DataLoader(pool)" to { com.lsp.hypersidebar.util.DataLoader.releaseForReload() },
            "FanPrewarmer(pool)" to { com.lsp.hypersidebar.util.FanPrewarmer.releaseForReload() },
        )) {
            runCatching { block() }.onFailure { HLog.w(TAG, "release [$n] failed: ${it.message}") }
        }
        return true
    }

    /**
     * API 102 热重载 · 第二拍：**新代**接手。
     *
     * ⚠ 框架**不会**重放 `onModuleLoaded` / `onPackageLoaded` / `onPackageReady`
     * ⇒ 所有 Java hook 必须在这里重装，否则新代等于没装（表现为"改完代码装包后功能消失，
     * 重启宿主才回来"）。
     *
     * 顺序：① 摘掉旧代句柄（`unhook`）→ ② 钩子字段与 isInit 复位 → ③ 按进程名重装 Java hooks
     * （`installFor`）→ ④ 重起 SystemUiInputSpy（它是 `object` 单例，不挂在 XposedModule
     * 生命周期上，onPackageReady 那条路不会再来）。
     *
     * **不得**重装 native：`System.loadLibrary("hypersidebar_native")` 只在 `onModuleLoaded`
     * 调；APK native 入口的 inline/PLT hook 绑在宿主 spawner 世代上，热重载期间卸载/重建
     * 它按框架文档属于禁用操作（参考工程 AGENTS.md 同款纪律）。native 侧在自己的进程内
     * 由 G3 巡检自愈，与本 Java 重载无关。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        HLog.i(TAG, "hot reload: onHotReloaded proc=${param.processName}")
        param.oldHookHandles.forEach { h ->
            runCatching { h.unhook() }
                .onFailure { HLog.w(TAG, "old hook unhook failed: ${it.message}") }
        }
        turboLayoutHook = null
        freeformRelayHook = null
        edgeGestureHook = null
        systemUiHook = null
        sidebarOverlayProbe = null
        backGestureSignalProbe = null
        earlyBridgeHooked = false   // 新代要重新武装日志桥（旧代的 attach hook 已随旧代作废）

        val procName = param.processName
        val host = hostFor(procName)
        if (host == null) {
            HLog.w(TAG, "hot reload: no host for proc=$procName（本进程不装 hooks）")
            return
        }
        val ctx = appContextOrNull()
        // ← 必须在 installFor 之前：不恢复 ezxhelper 状态，installFor 里每个 hook 的 init 都会抛
        // （base 未设 / 宿主类找不到），2026-10-16 真机实测如此。见 restoreEzXposedAfterReload。
        restoreEzXposedAfterReload(ctx, procName)
        installFor(host)
        // 宿主侧通道重挂（B3 补口，见 BaseHook.rearmHostSide 的注释）：
        // 这些注册体原本挂在 Application.attach 的钩子里，而热重载不重放 attach ⇒
        // 上一步 releaseForReload 摘掉之后必须在**新代**显式重挂，否则它们一直死到宿主重启。
        if (ctx == null) {
            HLog.w(TAG, "hot reload: no app context ⇒ 宿主侧通道未能重挂（重启宿主可恢复）")
        } else {
            for (h in reloadTargets().map { it.second }) {
                runCatching { h.rearmHostSide(ctx) }
                    .onFailure { HLog.w(TAG, "rearmHostSide [${h.name}] failed: ${it.message}") }
            }
        }
        // spy 是纯 object 单例，不由 initHooks 驱动 ⇒ 单独重起（它内部对重复 start 幂等）。
        // ⚠ prefs 必须用 SyncedPrefs 包装（与冷启动路径 SystemUiHook.hookClickReceiver 一致）：
        // 传裸 hookSidePrefs()（=LSPosed remotePrefs）时，读到的是一次性拉取的**死快照**，
        // ConfigSync 的推送只进 override 缓存 ⇒ spy 永远看不到设置页改动。
        // 2026-10-16 真机实测：热重载后 `blank-tap armed=false` 而模块侧已推送，根因即此。
        if (host == Host.SYSTEM_UI && ctx != null) {
            runCatching {
                com.lsp.hypersidebar.hook.SystemUiInputSpy.start(
                    ctx, com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                )
            }
                .onFailure { HLog.w(TAG, "hot reload: spy restart failed: ${it.message}") }
        }
        HLog.i(TAG, "hot reload: reinstalled for $procName (host=$host)")
    }

    /**
     * 热重载后恢复 ezxhelper 的进程级状态（**必备**，2026-10-16 真机实测）。
     *
     * 为什么：热重载**不重放** `onModuleLoaded` / `onPackageLoaded` / `onPackageReady`，而
     * ezxhelper 的三份状态正是在那三个回调里设的：
     *  - `EzXposed.base`（XposedInterface）← `onModuleLoaded` ⇒ 不补则 `MethodFinder` /
     *    `HookFactory` 抛 `UninitializedPropertyAccessException: lateinit property base has
     *    not been initialized`；
     *  - `EzXposed.modulePath` / `moduleRes` ← `initModuleResources()`（同样在 onModuleLoaded
     *    调用）⇒ 不补则 `NativeLibLoader` 报 "EzXposed.modulePath 未初始化"，DexKit 起不来；
     *  - `EzXReflection` 的宿主 ClassLoader ← `onPackageReady` ⇒ 不补则宿主类全部
     *    `ClassNotFoundException`（实测 `com.miui.home.recents.GestureStubView` 找不到）。
     * 三条都命中过：`onHotReloaded` 里第一次跑到 `installFor` 时每个 hook 都失败。
     *
     * 用的全是 ezxhelper 的公开入口，不是反射进去的；`ModuleLoadedParam` 只是形参（实现里
     * 只读 `base`，不读 param），所以这里给它一个最小实现即可。
     * 宿主 ClassLoader 优先取 `Application.attach` 记下的 context，退到
     * `ActivityThread.currentApplication()`；两处都拿不到就只打 W 继续（hook 会各自失败但
     * 不崩，重启宿主可恢复）。
     */
    private fun restoreEzXposedAfterReload(ctx: Context?, procName: String) {
        runCatching {
            EzXposed.initOnModuleLoaded(this, object : ModuleLoadedParam {
                override fun isSystemServer(): Boolean = false
                override fun getProcessName(): String = procName
            })
        }.onFailure { HLog.w(TAG, "hot reload: EzXposed.base 恢复失败: ${it.message}") }
        runCatching { EzXposed.initModuleResources() }
            .onFailure { HLog.w(TAG, "hot reload: initModuleResources 失败: ${it.message}") }
        val hostCl = (ctx ?: appContextOrNull())?.classLoader
        if (hostCl != null) {
            runCatching {
                io.github.kyuubiran.ezxhelper.core.EzXReflection.init(hostCl)
            }.onFailure { HLog.w(TAG, "hot reload: EzXReflection.init 失败: ${it.message}") }
        } else {
            HLog.w(TAG, "hot reload: 拿不到宿主 ClassLoader ⇒ 宿主类解析会失败（重启宿主可恢复）")
        }
        if (ctx != null) {
            runCatching { EzXposed.initAppContext(ctx, false) }
                .onFailure { HLog.w(TAG, "hot reload: initAppContext 失败: ${it.message}") }
        }
    }

    /** 当前已装的 hook 实例（名字用于日志；只含已创建的）。 */
    private fun reloadTargets(): List<Pair<String, BaseHook>> = listOfNotNull(
        turboLayoutHook?.let { "TurboLayout" to it },
        freeformRelayHook?.let { "FreeformRelay" to it },
        edgeGestureHook?.let { "EdgeGesture" to it },
        systemUiHook?.let { "SystemUiHook" to it },
        sidebarOverlayProbe?.let { "SidebarOverlayProbe" to it },
        backGestureSignalProbe?.let { "BackGestureSignalProbe" to it },
    )

    /** spy 重起要 context：从任意已装 hook 拿不到，直接取全局 app context（SystemUI 常驻）。 */
    private fun appContextOrNull(): Context? = runCatching {
        val clz = Class.forName("android.app.ActivityThread")
        val m = clz.getMethod("currentApplication")
        m.invoke(null) as? Context
    }.getOrNull()

    /** 单次守卫：attach hook 每进程只装一份（各 hook 自己的 attach hook 独立共存） */
    @Volatile
    private var earlyBridgeHooked = false

    /**
     * 日志桥无条件武装（onModuleLoaded 调用，全进程最早时刻）：hook
     * Application.attach 后只注册 LogDumpBridge，不依赖任何包事件分发/hook init。
     * HyperOS runtime（Rust 桌面）进程若有标准 Application 生命周期即被覆盖。
     * 方法找不到 = 记 W 级（本身就是证据）。
     */
    private fun armEarlyLogDumpBridge() {
        if (earlyBridgeHooked) return
        earlyBridgeHooked = true
        runCatching {
            val hooked = MethodFinder.fromClass(android.app.Application::class.java)
                .filterByName("attach")
                .filterByParamTypes(Context::class.java)
                .firstOrNull()
                ?.createAfterHook {
                    val ctx = it.args[0] as? Context ?: return@createAfterHook
                    LogDumpBridge.register(ctx)
                    HLog.i(TAG, "early log dump bridge armed (via Application.attach)")
                }
            if (hooked == null) HLog.w(TAG, "Application.attach hook 未找到方法，日志桥未武装")
        }.onFailure { HLog.w(TAG, "armEarlyLogDumpBridge failed: ${it.message}") }
    }

    /**
     * hook 进程侧 remote prefs 获取（0915 读 libxposed-service 源码纠正了此前的错误认知）。
     *
     * **事实**：读侧的 `RemotePreferences` 永远是 `newInstance` 那一刻的快照——它内部只有一份
     * 本地 `mMap`（`getXxx` 全读它），而 `IXposedService` 的 AIDL 里**没有任何 prefs 变更
     * 推送接口**；`registerOnSharedPreferenceChangeListener` 只对**本进程自己的写入**回调
     * （`Editor.doUpdate()`），hook 进程从不写这些键，且它内部用 `WeakHashMap` 存 listener。
     *
     * 所以此前那条「注册监听是承重件、注册后 push 全量到达、下一呼出即读到新值」的注释与
     * 对应实现都是错的（那个监听既不会被触发、还会被 GC 回收），已删除。
     * 配置实时性完全走 `ConfigSync` 广播 / `ConfigPullBridge` 拉取通道，
     * 读取分流由 `SyncedPrefs` 装饰器负责。
     */
    private fun hookSidePrefs(): SharedPreferences {
        val prefs = getRemotePreferences(PrefsFiles.REMOTE)
        // 高频明细日志开关（§11.2）：init 读一次，此后随 ConfigSync.applySync 刷新
        HLog.verboseEnabled = runCatching {
            prefs.getBoolean(com.lsp.hypersidebar.prefs.PrefKeys.DEBUG_VERBOSE_LOGS, false)
        }.getOrDefault(false)
        return prefs
    }

    private fun currentProcessName(): String {
        return try {
            val clz = Class.forName("android.app.ActivityThread")
            val method = clz.getMethod("currentProcessName")
            method.invoke(null) as? String ?: ""
        } catch (_: Exception) { "" }
    }

    private fun initHooks(vararg hooks: BaseHook) {
        for (h in hooks) {
            if (h.isInit) continue
            try {
                h.init()
                h.isInit = true
                HLog.i(TAG, "Hook [${h.name}] registered OK")
            } catch (e: Exception) {
                HLog.e(TAG, "Hook [${h.name}] FAILED: ${e.message}", e)
            }
        }
    }
}
