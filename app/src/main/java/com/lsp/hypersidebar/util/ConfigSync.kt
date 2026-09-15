package com.lsp.hypersidebar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.lsp.hypersidebar.BuildConfig
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.util.HLog

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
 * 广播借 root 启动任意非导出组件=提权），且伪造配置可注入扇形启动项。接收端仍无来源
 * 校验（广播信道的固有限制），攻击面从"全网可收"收窄为"仅宿主进程内代码"。
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

    /** SYNC 定向投递目标（唯一消费方=两个 hook 宿主；隐式广播任意 App 可收，禁止回退隐式） */
    private val HOOK_HOST_PKGS = listOf(HostPackages.HOME, HostPackages.UI_HOST)

    // ===== hook 进程侧：同步缓存 =====

    private val cache = ConcurrentHashMap<String, Any>()

    /** 最近一次收到 SYNC 的时刻（elapsedRealtime）；0=从未收到（init 快照兜底态） */
    @Volatile private var lastSyncAt = 0L

    /** hook 侧 context（回执广播用）；由 [registerHookSide] 记下 */
    @Volatile private var hookCtx: Context? = null

    /** 收到 SYNC 全量覆盖（键值均为 Bundle 可序列化基础类型/StringSet）。 */
    fun applySync(map: Map<String, Any>) {
        cache.clear()
        cache.putAll(map)
        lastSyncAt = android.os.SystemClock.elapsedRealtime()
        // 高频明细日志开关随全量配置刷新（设置页切换 → 三进程生效；§11.2）
        (map[com.lsp.hypersidebar.prefs.PrefKeys.DEBUG_VERBOSE_LOGS] as? Boolean)?.let {
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
    fun overrideSnapshot(): Map<String, Any> = HashMap(cache)

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
        runCatching {
            val writeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                sendSync(context, prefs, key)
            }
            moduleWriteListener = writeListener // 强引用存活，见字段注释
            prefs.registerOnSharedPreferenceChangeListener(writeListener)
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        sendSync(c, prefs)
                    }
                },
                IntentFilter(ACTION_REQUEST),
                Context.RECEIVER_EXPORTED
            )
            // 回执接收（仅诊断）：模块进程活着时才收得到——而"改设置"必然发生在本进程活着时，
            // 所以「日志页 → 本应用 → ack」是判定投递是否成功的有效信号
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        HLog.i(
                            TAG,
                            "ack: proc=${intent.getStringExtra("proc")} keys=${intent.getIntExtra("n", -1)}"
                        )
                    }
                },
                IntentFilter(ACTION_SYNC_ACK),
                Context.RECEIVER_EXPORTED
            )
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
     * 代价是常规写入会推两次（每次两次定向广播，异步 oneway，成本可忽略）。
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
     */
    fun sendSync(context: Context, prefs: SharedPreferences, changedKey: String? = null) {
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

    /**
     * hook 进程侧注册（幂等，挂在 Application.attach hook 内调用）：
     * - 收 SYNC → applySync
     * - 注册后延迟发 ACTION_REQUEST 请求模块回推（补 hook 先于模块启动的缺口）
     */
    fun registerHookSide(context: Context) {
        if (hookRegistered) return
        hookRegistered = true
        hookCtx = context.applicationContext
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val map = intent.getSerializableExtra("map") as? Map<*, *> ?: return
                        @Suppress("UNCHECKED_CAST")
                        applySync(map as Map<String, Any>)
                    }
                },
                IntentFilter(ACTION_SYNC),
                Context.RECEIVER_EXPORTED
            )
            // 请求模块回推：延迟 3s（等模块进程/接收器就绪；无应答则静默，
            // 用户下次打开设置页会再同步）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { context.sendBroadcast(Intent(ACTION_REQUEST)) }
            }, 3000L)
            HLog.i(TAG, "hook-side sync receiver registered")
        }.onFailure { HLog.w(TAG, "hook-side register failed: ${it.message}") }
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
