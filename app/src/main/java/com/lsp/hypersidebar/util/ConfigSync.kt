package com.lsp.hypersidebar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import com.lsp.hypersidebar.BuildConfig
import java.util.Collections
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.HLog

private const val MAX_SYNC_STRING_LEN = 64 * 1024
private const val MAX_SYNC_SET_SIZE = 512

/**
 * 同步载荷白名单化（2026-11-02 审查 P1）：安全键一律丢弃，只保留 Bundle 基础类型。
 *
 * `relayToken` 的唯一权威来源是 LSPosed remotePrefs（hook 侧自行读），广播通道纯属冗余；
 * 而 [ConfigSync.applySync] 的缓存经 [SyncedPrefs] **优先于** base 快照——不在这里截断，
 * 任意 App 一条 ACTION_SYNC 广播即可把 `:ui` 的 root 代发令牌改成自己的值。
 * 单点截断同时覆盖广播与 [ConfigPullBridge] 两条入口。
 */
internal fun sanitizeSyncMap(raw: Map<*, *>): Map<String, Any> {
    val out = HashMap<String, Any>(raw.size)
    for ((k, v) in raw) {
        val key = k as? String ?: continue
        if (key == PrefKeys.RELAY_TOKEN) continue
        when (v) {
            is String -> if (v.length <= MAX_SYNC_STRING_LEN) out[key] = v
            is Int, is Long, is Float, is Boolean -> out[key] = v
            is Set<*> -> if (v.size <= MAX_SYNC_SET_SIZE && v.all { it is String }) {
                @Suppress("UNCHECKED_CAST")
                out[key] = (v as Set<String>).toHashSet()
            }
            else -> Unit
        }
    }
    return out
}

/**
 * 配置同步广播通道（批次 2 / D3 根治方案）。
 *
 * 背景（源码+实测双重实证）：libxposed-service 101.0.0 的 RemotePreferences 是
 * newInstance 一次拉取的**不可变快照**，且 LSPosed 框架侧对 getRemotePreferences
 * 也做进程级缓存（重调 API 拿到同一死实例）——模块进程（设置页）的写入在 hook
 * 进程（com.miui.home）重启前**永远不可见**。任何"读 remotePrefs 实时生效"的
 * 假设都不成立。
 *
 * 通道设计：
 * - 模块进程：RemotePrefsBridge 绑定后注册 prefs listener——任何键写入即把
 *   **全量 getAll()** 以 [ACTION_SYNC] 广播推给 hook 进程；绑定完成本身也推一次
 *   （覆盖"hook 启动早于模块启动"的冷启动缺口）
 * - hook 进程：动态 receiver 收 SYNC → 更新 [ConfigCache]；启动后发
 *   [ACTION_REQUEST] 请求模块回推（模块进程活着时秒回，死了等用户开设置页）
 * - 读取：[SyncedPrefs] 装饰器——缓存命中优先，回落启动快照（双保险）
 *
 * 安全口径（0912 审查修订）：SYNC 广播**显式定向两个 hook 宿主**且**剔除 relayToken**——
 * 此前隐式全量广播把 root 代发防伪令牌送给了任意监听 App（拿到即可伪造 ShortcutRelay
 * 广播借 root 启动任意非导出组件=提权），且伪造配置可注入扇形启动项。
 *
 * 2026-11-02 审查补的闸（原「接收端无来源校验」的缺口）：
 * - 内容闸 [sanitizeSyncMap]（在 [applySync] 内，广播与 ConfigPullBridge 共用）：安全键
 *   `relayToken` 与非常规类型/超长值一律丢弃——伪造广播不再能改写缓存的令牌；
 * - 来源闸**放弃**（真机实测 `sentFromUid`/`sentFromPackage` 在本机 OS4/API 36 上对跨应用
 *   广播恒 null ⇒ 恒 fail-open，没有拦截能力）；因此也**不允许**再让令牌搭 SYNC 便车：
 *   令牌的正规分发路径是 remotePrefs 快照与 [ConfigPullService] 的 uid-ACL 拉取
 *   （[ConfigPullBridge] 拉到后 [RelayToken.adopt]）。
 * 涉及启动行为的广播（FAN_LAUNCH）仍走 RelayToken 严格校验。
 */
object ConfigSync {

    private const val TAG = "ConfigSync"
    // action 前缀取 BuildConfig（=gradle applicationId，编译期常量）：改包名自动跟随，
    // 不再与 FreeformLauncher.MODULE_PACKAGE 各写一份字面量
    const val ACTION_SYNC = BuildConfig.APPLICATION_ID + ".CONFIG_SYNC"
    const val ACTION_REQUEST = BuildConfig.APPLICATION_ID + ".CONFIG_REQUEST"

    /**
     * 收讫回执（hook 进程 → 模块 App，仅诊断用，载荷=进程短名 + 键数，无敏感值）。
     *
     * 存在的理由：`ConfigSync` 这条链有四个可能断点——模块侧没推 / 推了投递失败（宿主被
     * SmartPower 冻结时系统静默丢弃，见 ConfigPullService 注释）/ hook 侧收到没应用 /
     * 应用了但读取方没走 SyncedPrefs。原先只能靠 logcat 两头对日志，极易误判；有回执后
     * 「日志页 → 本应用 里有没有 `ack from launcher`」一句话就能把断点二分。
     */
    const val ACTION_SYNC_ACK = BuildConfig.APPLICATION_ID + ".CONFIG_SYNC_ACK"

    /**
     * SYNC 定向投递目标（唯一消费方=三个 hook 宿主；隐式广播任意 App 可收，禁止回退隐式）。
     * 2026-10-26 审查 B1：补 SystemUI——它的 SystemUiHook 已挂 registerHookSide 接收端
     * （接收器空转数代），spy 读的底角/blank-tap/接管开关因此只能靠启动快照；补上后
     * 设置页改动实时到达（省一次「重启系统界面」）。
     */
    private val HOOK_HOST_PKGS = listOf(HostPackages.HOME, HostPackages.UI_HOST, HostPackages.SYSTEM_UI)

    // ===== hook 进程侧：同步缓存 =====

    /**
     * 同步缓存（2026-10-26 审查 B5）：**整体换引用**而非 clear+putAll——后者存在
     * 「清空后、putAll 前」的瞬时空窗，并发读（手势线程经 SyncedPrefs）会回落到
     * base 启动快照（旧值）。引用替换是单个 volatile 写，读侧要么见旧全量、要么见
     * 新全量，永远不见空表。map 本身不再需要并发容器（无人原地写）。
     */
    @Volatile private var cache: Map<String, Any> = emptyMap()

    /** 最近一次收到 SYNC 的时刻（elapsedRealtime）；0=从未收到（init 快照兜底态） */
    @Volatile private var lastSyncAt = 0L

    /** hook 侧 context（回执广播用）；由 [registerHookSide] 记下 */
    @Volatile private var hookCtx: Context? = null

    /** 收到 SYNC 全量覆盖（键值均为 Bundle 可序列化基础类型/StringSet）。 */
    fun applySync(raw: Map<*, *>) {
        // 白名单化：安全键（relayToken）与非常规类型在此丢弃——伪造广播无法污染缓存
        val sanitized = sanitizeSyncMap(raw)
        cache = java.util.Collections.unmodifiableMap(sanitized)
        lastSyncAt = android.os.SystemClock.elapsedRealtime()
        if (sanitized.size != raw.size) {
            val dropped = raw.keys.mapNotNull { it as? String }
                .filter { it !in sanitized }
                .take(8)
            HLog.w(TAG, "sync dropped ${raw.size - sanitized.size} key(s): $dropped")
        }
        // 高频明细日志开关随全量配置刷新（设置页切换 → 三进程生效；§11.2）
        (sanitized[PrefKeys.DEBUG_VERBOSE_LOGS] as? Boolean)?.let {
            HLog.verboseEnabled = it
        }
        HLog.i(TAG, "config synced: ${cache.size} keys")
        sendAck(cache.size)
    }

    /** 回执：让模块 App 侧日志页能直接判定「推了到底有没有被收到」。 */
    private fun sendAck(keyCount: Int) {
        val c = hookCtx ?: return
        runCatching {
            c.sendBroadcast(
                Intent(ACTION_SYNC_ACK)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra("proc", HLog.proc())
                    .putExtra("n", keyCount)
            )
        }
    }

    fun overrideValue(key: String?): Any? = key?.let { cache[it] }
    fun containsOverride(key: String?): Boolean = key != null && cache.containsKey(key)
    fun hasOverride(): Boolean = cache.isNotEmpty()

    /** 缓存快照（本体已不可变，直接返回；调用方不得写） */
    fun overrideSnapshot(): Map<String, Any> = cache

    /**
     * 缓存新鲜度（迭代六 §11.1）：从未同步过，或距上次 SYNC 超过 [STALE_MS] 即视为过期。
     * 过期≠一定有新配置——只是"模块侧改过设置而 SYNC 广播可能被冻结宿主丢弃"的信号，
     * 由 [ConfigPullBridge] 决定是否 bind 拉取兜底。手势 DOWN/呼出装配前的低成本检查点
     * 只做这里的一次 volatile 读。
     */
    const val STALE_MS = 60_000L

    fun isStale(): Boolean {
        val last = lastSyncAt
        return last == 0L || android.os.SystemClock.elapsedRealtime() - last > STALE_MS
    }

    // ===== 模块进程侧：写入即推送 =====

    @Volatile private var moduleRegistered = false

    /**
     * ⚠️ 写入监听**必须用字段强引用持有**（根因修复，0915 读 libxposed-service 源码定案）。
     *
     * `RemotePreferences` 内部是
     * `Map<OnSharedPreferenceChangeListener, Object> mListeners = new WeakHashMap<>()`，
     * 且它**没有**任何服务端推送入口——`registerOnSharedPreferenceChangeListener` 只是把
     * listener 放进这张弱表，唯一的回调时机是本地 `Editor.doUpdate()`（即本进程自己写）。
     * 所以裸 lambda 注册后一旦没有强引用，下一次 GC 就被回收，推送**静默停摆**，
     * 模块侧只剩「绑定完成即推一次」——症状正是「改完设置不生效、重开设置 App 才生效」。
     */
    @Volatile
    private var moduleWriteListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // 模块侧写入路径需要「写完就推」，但写入点（savePref 扩展、commitDraft、手写 edit()）
    // 拿不到 Context——绑定完成时把二者存下来，由 [notifyConfigChanged] 复用。
    @Volatile private var moduleCtx: Context? = null
    @Volatile private var modulePrefs: SharedPreferences? = null

    /**
     * 合并窗口（2026-11-02 审查 P2）：原先每个键写入都立刻推一次全量——
     * `restoreAllDefaults()` 一次点击（~30 个 put + 一次 notify）会打出 ~31 轮
     * 「3 个宿主的定向广播 + CornerGeomBridge×2 + NativeGateBridge」。
     * 全量快照覆盖、无顺序依赖，故用 [SYNC_COALESCE_MS] 窗口把连发合并成一次。
     */
    private const val SYNC_COALESCE_MS = 150L

    /** 合并用的 Handler（主线程 Looper：模块进程的写入可能来自任意线程）。 */
    @Volatile private var moduleSyncHandler: android.os.Handler? = null

    /** 窗口内最后一次写入的键（仅日志定位用）。 */
    @Volatile private var pendingChangedKey: String? = null

    /** 窗口到点：一次性推全量。引用 [moduleCtx]/[modulePrefs]——所有 sendSync 调用点
     *  都发生在 [ensureModuleSide] 之后，两者必已就绪；缺失则静默丢弃（进程正在退休）。 */
    private val syncFlush = Runnable {
        val c = moduleCtx ?: return@Runnable
        val p = modulePrefs ?: return@Runnable
        val changed = pendingChangedKey
        pendingChangedKey = null
        doSendSync(c, p, changed)
    }

    /**
     * 模块进程侧注册（幂等）：
     * - prefs 任何键写入即全量广播
     * - 绑定完成后立即推一次（设置页打开=配置刷新）
     * - 应答 hook 进程的 ACTION_REQUEST（补 hook 先于模块启动的缺口；
     *   模块进程死了收不到，等用户开设置页自然补推）
     */
    fun ensureModuleSide(context: Context, prefs: SharedPreferences) {
        moduleCtx = context.applicationContext
        modulePrefs = prefs
        if (moduleRegistered) return
        moduleRegistered = true
        // 只在**真正注册**时记注册 ctx：后到的调用可能带另一个 context 实例（Activity vs
        // Application），覆盖了就与注册键不一致 ⇒ 注销会失败
        moduleRegCtx = context
        runCatching {
            val writeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                sendSync(context, prefs, key)
            }
            moduleWriteListener = writeListener // 强引用存活，见字段注释
            prefs.registerOnSharedPreferenceChangeListener(writeListener)
            val requestReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    sendSync(c, prefs)
                }
            }
            context.registerReceiver(
                requestReceiver,
                IntentFilter(ACTION_REQUEST),
                Context.RECEIVER_EXPORTED
            )
            moduleRequestReceiver = requestReceiver
            // 回执接收（仅诊断）：模块进程活着时才收得到——而"改设置"必然发生在本进程活着时，
            // 所以「日志页 → 本应用 → ack」是判定投递是否成功的有效信号
            val ackReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    HLog.i(
                        TAG,
                        "ack: proc=${intent.getStringExtra("proc")} keys=${intent.getIntExtra("n", -1)}"
                    )
                }
            }
            context.registerReceiver(
                ackReceiver,
                IntentFilter(ACTION_SYNC_ACK),
                Context.RECEIVER_EXPORTED
            )
            moduleAckReceiver = ackReceiver
            // 绑定完成即推一次：hook 进程启动早于模块进程时靠这条补齐
            sendSync(context, prefs)
        }
    }

    /**
     * 模块进程侧：任何写入路径都可显式调用一次推送（[changedKey] 仅供日志定位）。
     *
     * 与上面那条写入监听是**双保险**，两者都要留：
     * - 监听是主通道（覆盖所有走 `Editor` 的写入），但它的存活依赖上面的强引用；
     * - 显式推送不依赖任何监听存活，且让「哪个写入点触发了一次同步」在代码上一眼可见
     *   （排查时不必再回头确认监听活着没）。
     * 代价是常规写入会触发两次排程——但两者落在同一个 `SYNC_COALESCE_MS` 窗口里会被
     * 合并成一次发送（[sendSync]），不成倍放大。
     * 未注册（如 hook 进程误调）时静默 no-op。
     */
    fun notifyConfigChanged(changedKey: String? = null) {
        val c = moduleCtx ?: return
        val p = modulePrefs ?: return
        sendSync(c, p, changedKey)
    }

    /**
     * 全量广播当前配置（getAll 值均 Serializable：String/Int/Float/Boolean/StringSet）。
     * relayToken 必须剔除：hook 宿主经 LSPosed remotePrefs 自行读令牌，广播通道纯属冗余；
     * 带令牌的广播一旦离开定向范围即等于把 root 代发防伪令牌送人。
     *
     * 本函数只做**排程**：`SYNC_COALESCE_MS` 窗口内重复调用合并为一次 [doSendSync]
     * （全量快照覆盖，无顺序依赖）。真正发送见 [doSendSync]。
     */
    fun sendSync(context: Context, prefs: SharedPreferences, changedKey: String? = null) {
        val handler = moduleSyncHandler
            ?: android.os.Handler(android.os.Looper.getMainLooper()).also { moduleSyncHandler = it }
        if (changedKey != null) pendingChangedKey = changedKey
        handler.removeCallbacks(syncFlush)
        handler.postDelayed(syncFlush, SYNC_COALESCE_MS)
    }

    /** 真正发送（[sendSync] 的合并窗口到点后调用；参数仅供日志与显式路径使用）。 */
    private fun doSendSync(context: Context, prefs: SharedPreferences, changedKey: String?) {
        // 顺带把底角几何推给 native（CornerGeomBridge）：native 门控跑在 com.miui.home，
        // 而 OS4 上该进程没有模块 Java 半侧 ⇒ 配置同步广播到不了它，只能走系统属性。
        // 幂等（值没变则跳过），异步不阻塞；无 root 时静默失败（native 用兜底值）。
        runCatching { CornerGeomBridge.push(context, prefs) }
            .onFailure { HLog.w(TAG, "corner geom push failed: ${it.message}") }
        // 同一条通道推侧滑触发带（native 侧滑线 S3 抑制档判"带内起手"用）。
        // 与角区那条独立：角区送比例、这条送 px，理由见 CornerGeomBridge 类注释。
        runCatching { CornerGeomBridge.pushBand(prefs) }
            .onFailure { HLog.w(TAG, "band geom push failed: ${it.message}") }
        // 横竖切换后必须重推一次带（方向位在属性里，屏尺寸是竖屏归一静态值）：在这里顺手
        // 挂上旋转监听（幂等）。不挂的话，转屏后 native 会一直按旧朝向判定（2026-10-13 修）。
        runCatching { CornerGeomBridge.ensureRotationWatch(context, prefs) }
            .onFailure { HLog.w(TAG, "rotation watch failed: ${it.message}") }
        // native 桌面 hook 总闸（2026-10-14「默认化」；当晚收敛为**只写关断**）：模块开关
        // 与"本机是不是 OS4 纯 native 桌面"合起来只决定一件事——要不要写那个免 root 的
        // 关断 marker（`Android/media/<applicationId>/hsb_native_gate`，内容 "0"）。
        // "开"= 删掉残留 marker（native 侧 fail-open：属性/marker 都不表态即开），
        // 所以这里既不写属性也不用 su（真机两条属性写路都被 SELinux 拒）。
        // ⚠ 只写通道值，**不重启任何宿主**（用户 m02478 明令）：值变化等下次桌面启动生效，
        // 要立刻生效由用户自己重启桌面/点设置页的「重启 hook 宿主」。
        runCatching { NativeGateBridge.push(prefs) }
            .onFailure { HLog.w(TAG, "native gate push failed: ${it.message}") }
        runCatching {
            val map = HashMap<String, Any>()
            for ((k, v) in prefs.all) {
                if (k == com.lsp.hypersidebar.prefs.PrefKeys.RELAY_TOKEN) continue
                if (k != null && v != null) map[k] = v
            }
            for (host in HOOK_HOST_PKGS) {
                val intent = Intent(ACTION_SYNC)
                    .setPackage(host)
                    .putExtra("map", map as java.io.Serializable)
                context.sendBroadcast(intent)
            }
            // 诊断锚点：与 hook 侧 "config synced" 配对——推了没收到=投递问题，
            // 没推=写入监听/绑定问题
            HLog.i(TAG, "sendSync: ${map.size} keys, changed=$changedKey")
        }.onFailure { HLog.w(TAG, "sendSync failed: ${it.message}") }
    }

    // ===== hook 进程侧：receiver 注册 =====

    @Volatile private var hookRegistered = false

    /** hook 侧 SYNC 接收器：**原先是匿名注册（无处注销）**——API 102 热重载下 AMS 持有的
     *  receiver 会钉住旧代 classloader，而新代再注册一份就会出现两份 applySync。 */
    @Volatile private var hookSyncReceiver: BroadcastReceiver? = null

    /**
     * **注册时用的那个 context 实例**（≠ [hookCtx]）。
     *
     * 为什么必须分开存：`LoadedApk` 的 receiver 表以"外层 context"为键（`getOuterContext()`），
     * 注销必须用**同一个 context 实例**，否则 `unregisterReceiver` 抛
     * IllegalArgumentException（实则没摘掉）。而 [hookCtx] 存的是 `context.applicationContext`
     * ——attach 时刻它可能还是 null（1004/1005 实锤），且即使非 null 也不保证与注册用的那个
     * 实例同键。注销一律用本字段。
     */
    @Volatile private var hookRegCtx: Context? = null

    /** 注册后那个"延迟 3s 请求回推"用的 Handler：存引用才能在热重载时清掉排队回调
     *  （被排队的 Runnable 是**旧代闭包**，不清就得等它跑完旧代才可能被回收）。 */
    @Volatile private var hookRequestHandler: android.os.Handler? = null

    /** 模块侧两个接收器（ACTION_REQUEST 应答 / ACK 回执）的引用，同上：匿名注册无处注销。 */
    @Volatile private var moduleRequestReceiver: BroadcastReceiver? = null
    @Volatile private var moduleAckReceiver: BroadcastReceiver? = null

    /** 模块侧**注册时用的那个 context 实例**（理由同 [hookRegCtx]：注销必须同键）。 */
    @Volatile private var moduleRegCtx: Context? = null

    /**
     * hook 进程侧注册（幂等，挂在 Application.attach hook 内调用）：
     * - 收 SYNC → applySync
     * - 注册后延迟发 ACTION_REQUEST 请求模块回推（补 hook 先于模块启动的缺口）
     *
     * ⚠ 同时是 **API 102 热重载的重挂入口**（由各 hook 的 `rearmHostSide` 调）：冷启动
     * attach 路径与热重载重挂共用本函数（唯一实现）；`hookRegistered` 守卫在
     * [releaseForReload] 里被复位，所以重挂能真正再注册一次，并顺带重发一次
     * ACTION_REQUEST（热重载后立即拉一次最新配置）。
     */
    fun registerHookSide(context: Context) {
        if (hookRegistered) return
        hookRegistered = true
        hookCtx = context.applicationContext
        hookRegCtx = context
        runCatching {
            val syncRecv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val map = intent.getSerializableExtra("map") as? Map<*, *> ?: return
                    // 来源闸已放弃（2026-11-02 真机实测）：`sentFromUid`/`sentFromPackage`
                    // 在本机 OS4/API 36 上对跨应用广播恒为 null/INVALID ⇒ 恒 fail-open，
                    // 只带来虚假安全感（伪造回包实测被放行）。硬边界在 applySync 的
                    // 内容过滤：安全键与类型在这里被丢弃，广播通道已无令牌可窃取。
                    applySync(map)
                }
            }
            context.registerReceiver(
                syncRecv,
                IntentFilter(ACTION_SYNC),
                Context.RECEIVER_EXPORTED
            )
            hookSyncReceiver = syncRecv
            // 请求模块回推：延迟 3s（等模块进程/接收器就绪；无应答则静默，
            // 用户下次打开设置页会再同步）
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            hookRequestHandler = handler
            handler.postDelayed({
                runCatching { context.sendBroadcast(Intent(ACTION_REQUEST)) }
            }, 3000L)
            HLog.i(TAG, "hook-side sync receiver registered")
        }.onFailure { HLog.w(TAG, "hook-side register failed: ${it.message}") }
    }

    /**
     * API 102 热重载释放（旧代退休前调用；幂等，两侧都摘）。
     *
     * 为什么必须：本 object 在两个方向各注册了广播接收器 / prefs 写入监听——
     * 它们全都是**宿主长期持有**的对象（AMS 的 receiver 表、`RemotePreferences` 的
     * listener 表），不摘掉旧代 classloader 回收不了；而新代再注册一份就会出现
     * "两份 applySync / 两次 sendSync"。排队在 Handler 上的延迟回推 Runnable 同理
     * （它是旧代闭包）。
     *
     * ⚠ 只释放**注册**，不清 `cache`：配置缓存是数据不是资源，清掉会让读取瞬间回落到
     * 启动快照（旧值），在同代内若不再收到 SYNC 就会一直停在旧值上。
     * 模块侧的 `moduleCtx`/`modulePrefs` 也**保留**（它们是纯引用，且 `notifyConfigChanged`
     * 靠它们继续工作——模块进程自己的写入推送不受本释放影响）。
     */
    fun releaseForReload() {
        // ===== hook 进程侧 =====
        runCatching {
            val r = hookSyncReceiver
            val c = hookRegCtx
            if (r != null && c != null) c.unregisterReceiver(r)
        }.onFailure { HLog.w(TAG, "hook-side unregister failed: ${it.message}") }
        hookSyncReceiver = null
        hookRegCtx = null
        runCatching { hookRequestHandler?.removeCallbacksAndMessages(null) }
            .onFailure { HLog.w(TAG, "hook request callback cleanup failed: ${it.message}") }
        hookRequestHandler = null
        hookCtx = null
        hookRegistered = false
        // ===== 模块进程侧 =====
        runCatching {
            val c = moduleRegCtx
            if (c != null) {
                moduleRequestReceiver?.let { c.unregisterReceiver(it) }
                moduleAckReceiver?.let { c.unregisterReceiver(it) }
            }
        }.onFailure { HLog.w(TAG, "module-side unregister failed: ${it.message}") }
        moduleRequestReceiver = null
        moduleAckReceiver = null
        moduleRegCtx = null
        runCatching {
            val l = moduleWriteListener
            val p = modulePrefs
            if (l != null && p != null) p.unregisterOnSharedPreferenceChangeListener(l)
        }.onFailure { HLog.w(TAG, "write listener unregister failed: ${it.message}") }
        moduleWriteListener = null
        moduleRegistered = false
        // 合并窗口（2026-11-02）：排队中的 flush 是**旧代闭包**，必须清掉，
        // 否则热重载后旧代仍会跑一次推送
        runCatching { moduleSyncHandler?.removeCallbacksAndMessages(null) }
            .onFailure { HLog.w(TAG, "sync handler cleanup failed: ${it.message}") }
        moduleSyncHandler = null
        pendingChangedKey = null
        // ===== 模块进程侧的另外两条 util 通道 =====
        // 它俩的唯一调用点就是本对象的 [sendSync]（`CornerGeomBridge.push`/`pushBand`/
        // `ensureRotationWatch`、`NativeGateBridge.push`），所以由这里统一停：
        // CornerGeomBridge 要摘 Application 上的旋转监听并停 su 执行器，NativeGateBridge 要停
        // 写 marker 的执行器——两条都是"旧代闭包/线程钉住旧 classloader"。
        // 重挂不需要专门入口：它们的下一次 `sendSync`（设置页任一写入/绑定完成）会重建执行器
        // 并重挂旋转监听（`rotationWatchArmed`/`lastWish` 都已在释放时复位）。
        runCatching { CornerGeomBridge.releaseForReload() }
            .onFailure { HLog.w(TAG, "corner geom release failed: ${it.message}") }
        runCatching { NativeGateBridge.releaseForReload() }
            .onFailure { HLog.w(TAG, "native gate release failed: ${it.message}") }
        HLog.i(TAG, "releaseForReload: hook/module receivers dismissed (cache kept: ${cache.size} keys)")
    }
}

/**
 * 同步感知的 prefs 装饰器（hook 进程用）：读取**缓存命中优先**、回落启动快照
 * （base），写入透传 base（hook 进程仅熔断状态会写，进程内语义不变）。
 * 用它包装 remotePrefs 后，读取方（FanMenuController/EdgeGestureHook）零改动
 * 即获得实时配置。
 */
class SyncedPrefs(private val base: SharedPreferences) : SharedPreferences {

    override fun getAll(): Map<String, *> =
        if (ConfigSync.hasOverride()) HashMap(ConfigSync.overrideSnapshot()) else base.all

    override fun getString(key: String?, defValue: String?): String? =
        (ConfigSync.overrideValue(key) as? String) ?: base.getString(key, defValue)

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (ConfigSync.overrideValue(key) as? Set<String>)?.toMutableSet()
            ?: base.getStringSet(key, defValues)

    override fun getInt(key: String?, defValue: Int): Int =
        (ConfigSync.overrideValue(key) as? Int) ?: base.getInt(key, defValue)

    override fun getLong(key: String?, defValue: Long): Long =
        (ConfigSync.overrideValue(key) as? Long) ?: base.getLong(key, defValue)

    override fun getFloat(key: String?, defValue: Float): Float =
        (ConfigSync.overrideValue(key) as? Float) ?: base.getFloat(key, defValue)

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (ConfigSync.overrideValue(key) as? Boolean) ?: base.getBoolean(key, defValue)

    override fun contains(key: String?): Boolean =
        ConfigSync.containsOverride(key) || base.contains(key)

    override fun edit(): SharedPreferences.Editor = base.edit()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = base.registerOnSharedPreferenceChangeListener(listener)

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = base.unregisterOnSharedPreferenceChangeListener(listener)
}
