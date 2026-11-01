package com.lsp.hypersidebar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.lsp.hypersidebar.prefs.PrefKeys
import org.json.JSONObject

/**
 * 日志拉取回传端（迭代六 §11.2，hook 进程侧）。
 *
 * 与 manifest 快捷方式桥同模式：模块 App（日志页/自检 v2）发 [PrefKeys.LOG_DUMP_REQUEST]
 * 广播，本进程接收器把 HLog 环形缓冲 JSON + 进程状态快照（熔断等）以
 * [PrefKeys.LOG_DUMP_REPLY] 定向回传。模块 App 前台=活进程，必收。
 *
 * 注册点：EdgeGestureHook（launcher）/FreeformRelayHook（:ui）/ **SystemUiHook（sys）**
 * 的 Application.attach，与探针接收器同款生命周期。
 *
 * ⚠️ **新增 hook 进程时必须同时在此注册** —— 否则该进程日志不进自检报告。
 * 09-27 报告即为此盲区的实例：SystemUI 未注册，磁贴通道全盲（无法判断 hook 是否加载、
 * 适配器是否解析、点击走到哪一档），排查只能靠猜。
 */
object LogDumpBridge {

    /** 幂等守卫：XposedInit 提前武装（不等宿主 hook）与各 hook 的 attach 注册可共存 */
    @Volatile
    private var registered = false

    /** 注册用的原始 context（注销必须用同一 context 实例） */
    @Volatile
    private var registeredCtx: Context? = null

    /**
     * 接收器引用：**原先是匿名注册（无处注销）**，这在 API 102 热重载下是硬伤——
     * AMS 持有已注册 receiver ⇒ 旧代 classloader 回收不了，且新代再注册一份=两份回传端
     * （模块 App 一次请求收到两份日志分块，自检报告出现重复块）。
     */
    @Volatile
    private var receiver: BroadcastReceiver? = null

    /** provider 存字段不存闭包：后续 register 调用升级替换（取快照在应答时求值） */
    @Volatile
    private var statusProvider: () -> String? = { null }

    @Volatile
    private var statsProvider: () -> String? = { null }

    /** 进程启动时刻（uptime）：object 首次触及即初始化（XposedInit 极早期武装路径），
     *  用于识别"新包装了但 :ui 旧启动"这类历史事故（os4-trigger-final-plan §4 P0-2） */
    private val procStartUptime = SystemClock.uptimeMillis()

    /** 分块大小：1000 条全量 ≈150KB，200 条/块 ≈30KB 远离 binder 1MB 上限 */
    private const val CHUNK = 200

    /**
     * 接收器只注册一次；provider 后到者覆盖（EdgeGestureHook 带 CircuitBreaker/StatsRecorder
     * 的富注册到达时自动顶掉 XposedInit 早期注册的空 provider）。
     *
     * ⚠ 同时是 **API 102 热重载的重挂入口**（由各 hook 的 `rearmHostSide` 调）：冷启动
     * attach 路径、`XposedInit.armEarlyLogDumpBridge` 的早期武装与热重载重挂共用本函数
     * （唯一实现）。[releaseForReload] 已把 `registered` 复位，故重挂能真正再注册，
     * 且 provider 会按调用方重新升级（:ui/launcher 的富 provider 不会退化成空）。
     *
     * @param statusProvider 进程状态快照（JSON 字符串，可 null）——launcher/:ui 传
     *   CircuitBreaker.snapshot()，模块 App 不注册本接收器（直读本地缓冲）
     * @param statsProvider 使用数据聚合 JSON（§11.3，可 null）——launcher/:ui 传
     *   StatsRecorder.dump()
     */
    fun register(
        context: Context,
        statusProvider: () -> String? = { null },
        statsProvider: () -> String? = { null }
    ) {
        this.statusProvider = statusProvider
        this.statsProvider = statsProvider
        // 换包陈旧检测（2026-10-31）：本函数是"每个宿主进程 attach 期"的唯一公共落点，
        // 陈旧才写一条 W（正常静默），详见 ModuleStaleness。
        runCatching { ModuleStaleness.logOnce(context) }
        if (registered) return
        registered = true
        registeredCtx = context
        runCatching {
            val recv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val roundId = intent.getLongExtra(PrefKeys.LOG_DUMP_EXTRA_ROUND, 0L)
                    // native 现状即时回灌（spike 取证）：installed/hits 是运行期累积量，
                    // 模块加载时那份快照看不到；logcat 在这台 ROM 上不可靠 ⇒ 导出必须带活体
                    runCatching { NativeProbe.report() }.getOrNull()
                        ?.split('\n')
                        ?.forEach { HLog.i("HSBNative", "live: $it") }
                    val entries = HLog.snapshot()
                    val meta = JSONObject()
                        .put("entries", entries.size)
                        .put("dropped", HLog.dropped)
                        .put("verbose", HLog.verboseEnabled)
                        .put("upMs", procStartUptime)
                    val chunks = entries.chunked(CHUNK)
                    val total = chunks.size
                    chunks.forEachIndexed { seq, chunk ->
                        val arr = org.json.JSONArray()
                        chunk.forEach { arr.put(it.toJson()) }
                        val reply = Intent(PrefKeys.LOG_DUMP_REPLY)
                            .setPackage(FreeformLauncher.MODULE_PACKAGE)
                            .putExtra(PrefKeys.LOG_DUMP_EXTRA_PROC, HLog.proc())
                            .putExtra(PrefKeys.LOG_DUMP_EXTRA_ROUND, roundId)
                            .putExtra(PrefKeys.LOG_DUMP_EXTRA_CHUNK_SEQ, seq)
                            .putExtra(PrefKeys.LOG_DUMP_EXTRA_CHUNK_TOTAL, total)
                            .putExtra(PrefKeys.LOG_DUMP_EXTRA_META, meta.toString())
                            .putExtra(PrefKeys.LOG_DUMP_EXTRA_LOGS, arr.toString())
                        this@LogDumpBridge.statusProvider()?.takeIf { seq == 0 }?.let {
                            reply.putExtra(PrefKeys.LOG_DUMP_EXTRA_STATUS, it)
                        }
                        this@LogDumpBridge.statsProvider()?.takeIf { seq == 0 }?.let {
                            reply.putExtra(PrefKeys.LOG_DUMP_EXTRA_STATS, it)
                        }
                        runCatching { c.sendBroadcast(reply) }
                            .onFailure { HLog.w("LogDump", "reply send failed: ${it.message}") }
                    }
                }
            }
            context.registerReceiver(
                recv,
                IntentFilter(PrefKeys.LOG_DUMP_REQUEST),
                Context.RECEIVER_EXPORTED
            )
            receiver = recv
            HLog.i("LogDump", "log dump receiver registered")
        }.onFailure { HLog.w("LogDump", "register failed: ${it.message}") }
    }

    /**
     * API 102 热重载释放（旧代退休前调用；幂等）。
     *
     * 为什么必须：本 object 在宿主进程（launcher / :ui / sys）注册了一个回传接收器，
     * 它是**宿主 AMS 持有**的对象——不解注册，旧代 classloader 回收不了；而新代再注册
     * 一份就会出现两个回传端（一次日志请求收到两份分块，自检报告出现重复块）。
     * provider 是旧代 lambda（闭包捕获旧代的 CircuitBreaker/StatsRecorder），一并复位，
     * 避免新代注册前的窗口期用到旧代对象。
     */
    fun releaseForReload() {
        runCatching {
            val r = receiver
            val c = registeredCtx
            if (r != null && c != null) c.unregisterReceiver(r)
        }.onFailure { HLog.w("LogDump", "unregister failed: ${it.message}") }
        receiver = null
        registeredCtx = null
        registered = false
        statusProvider = { null }
        statsProvider = { null }
    }
}
