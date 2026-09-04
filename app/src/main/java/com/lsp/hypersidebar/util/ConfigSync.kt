package com.lsp.hypersidebar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
 * 安全口径：SYNC 广播**不带令牌校验**——伪造的代价是"侧边栏观感被改"（低危害），
 * 而冷启动序下令牌可能尚未生成（拒掉合法同步的代价更高）。涉及启动行为的
 * 广播（FAN_LAUNCH）仍走 RelayToken 严格校验。
 */
object ConfigSync {

    private const val TAG = "ConfigSync"
    const val ACTION_SYNC = "io.github.mikudayoooooooo.hypersidebar.CONFIG_SYNC"
    const val ACTION_REQUEST = "io.github.mikudayoooooooo.hypersidebar.CONFIG_REQUEST"
    private const val GROUP = "hyperSidebar"

    // ===== hook 进程侧：同步缓存 =====

    private val cache = ConcurrentHashMap<String, Any>()

    /** 收到 SYNC 全量覆盖（键值均为 Bundle 可序列化基础类型/StringSet）。 */
    fun applySync(map: Map<String, Any>) {
        cache.clear()
        cache.putAll(map)
        Log.i(TAG, "config synced: ${cache.size} keys")
    }

    fun overrideValue(key: String?): Any? = key?.let { cache[it] }
    fun containsOverride(key: String?): Boolean = key != null && cache.containsKey(key)
    fun hasOverride(): Boolean = cache.isNotEmpty()
    fun overrideSnapshot(): Map<String, Any> = HashMap(cache)

    // ===== 模块进程侧：写入即推送 =====

    @Volatile private var moduleRegistered = false

    /**
     * 模块进程侧注册（幂等）：
     * - prefs 任何键写入即全量广播
     * - 绑定完成后立即推一次（设置页打开=配置刷新）
     * - 应答 hook 进程的 ACTION_REQUEST（补 hook 先于模块启动的缺口；
     *   模块进程死了收不到，等用户开设置页自然补推）
     */
    fun ensureModuleSide(context: Context, prefs: SharedPreferences) {
        if (moduleRegistered) return
        moduleRegistered = true
        runCatching {
            prefs.registerOnSharedPreferenceChangeListener { _, _ ->
                sendSync(context, prefs)
            }
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        sendSync(c, prefs)
                    }
                },
                IntentFilter(ACTION_REQUEST),
                Context.RECEIVER_EXPORTED
            )
            // 绑定完成即推一次：hook 进程启动早于模块进程时靠这条补齐
            sendSync(context, prefs)
        }
    }

    /** 全量广播当前配置（getAll 值均 Serializable：String/Int/Float/Boolean/StringSet）。 */
    fun sendSync(context: Context, prefs: SharedPreferences) {
        runCatching {
            val map = HashMap<String, Any>()
            for ((k, v) in prefs.all) {
                if (k != null && v != null) map[k] = v
            }
            val intent = Intent(ACTION_SYNC)
                .putExtra("map", map as java.io.Serializable)
            context.sendBroadcast(intent)
            // 诊断锚点：与 hook 侧 "config synced" 配对——推了没收到=投递问题，
            // 没推=写入监听/绑定问题
            Log.i(TAG, "sendSync: ${map.size} keys, iconSize=${map[com.lsp.hypersidebar.prefs.PrefKeys.ICON_SIZE]}")
        }.onFailure { Log.w(TAG, "sendSync failed: ${it.message}") }
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
            Log.i(TAG, "hook-side sync receiver registered")
        }.onFailure { Log.w(TAG, "hook-side register failed: ${it.message}") }
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
