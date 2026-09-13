package com.lsp.hypersidebar.util

import android.os.SystemClock
import android.util.Log
import com.lsp.hypersidebar.prefs.HostPackages
import org.json.JSONArray
import org.json.JSONObject

/**
 * 进程内结构化日志环形缓冲（迭代六 §11.2）。
 *
 * 设计：三进程（launcher/:ui/模块 App）各持一份纯内存缓冲——写入是一次数组覆盖
 * （写满 [CAPACITY] 条淘汰最旧），零 I/O、零 binder，热路径开销微秒级；文件写只
 * 发生在模块 App 拉取/导出时。自检 v2 与日志页的数据源。
 *
 * 分层：
 * - i/w/e 恒开（缓冲 + 照常落 logcat）——故障发生时没机会先开开关，基础层必须常在；
 * - 逐手势高频明细（g#N/s#N 系列）由调用方以 [verboseEnabled] 门控（默认关、
 *   release 可开，PrefKeys.DEBUG_VERBOSE_LOGS 运行时生效）——关掉时连字符串都不构造，
 *   「日志泄露→性能」审计项的修法。
 *
 * 脱敏红线（写入时强制，沿用既有定案）：RelayToken 绝不入缓冲；intentUri 载荷截断。
 * hook 进程注入本模块代码，跨进程拉取侧（模块 App）无法信任源端已脱敏，故统一在
 * 写入口脱敏。
 */
object HLog {

    /**
     * 进程短名（[proc] 产出 ↔ LogPage 过滤 chip 消费，0912 收口单源）。
     * "app" 分支匹配 applicationId（=真实进程名，经 BuildConfig 单源）而非源码
     * namespace——包名迁移后两者分叉，曾致模块进程短名落进 else 兜底、
     * 日志页"本应用"过滤 chip 永远过滤不到东西。
     */
    const val PROC_LAUNCHER = "launcher"
    const val PROC_UI = "ui"
    const val PROC_SYS = "sys"
    const val PROC_APP = "app"

    /** 缓冲容量（约 150KB/进程，实现假设值 §11.2） */
    private const val CAPACITY = 1000

    /**
     * 高频明细开关（volatile，无锁读）。赋值点：各进程 hook init 时读 prefs 一次 +
     * ConfigSync.applySync 收到全量配置时刷新（设置页切换 → 广播/pull → 三进程生效）。
     * 模块 App 进程内切换即时生效（SettingsPage 写 prefs 时同步置位）。
     */
    @Volatile
    var verboseEnabled: Boolean = false

    class Entry(
        val wallMs: Long,
        val level: Char,
        val proc: String,
        val tag: String,
        val msg: String,
    ) {
        fun formatLine(): String {
            val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(wallMs))
            return "$t ${levelIcon()} [$proc/$tag] $msg"
        }

        fun levelIcon(): String = when (level) {
            'E' -> "E"
            'W' -> "W"
            'I' -> "I"
            else -> "D"
        }

        fun toJson(): JSONObject = JSONObject()
            .put("ts", wallMs)
            .put("lvl", level.toString())
            .put("proc", proc)
            .put("tag", tag)
            .put("msg", msg)
    }

    private val buf = ArrayDeque<Entry>(CAPACITY)
    @Volatile private var procName: String? = null

    /** 进程短名（首次解析后缓存）：launcher / ui / sys / app */
    fun proc(): String {
        procName?.let { return it }
        val raw = runCatching {
            val clz = Class.forName("android.app.ActivityThread")
            clz.getMethod("currentProcessName").invoke(null) as? String
        }.getOrNull() ?: "?"
        val short = when {
            raw == HostPackages.HOME -> PROC_LAUNCHER
            raw.endsWith(HostPackages.UI_PROCESS_SUFFIX) -> PROC_UI
            raw == HostPackages.SYSTEM_UI -> PROC_SYS
            raw == FreeformLauncher.MODULE_PACKAGE || raw.isEmpty() -> PROC_APP
            else -> raw.substringAfterLast('.').take(12)
        }
        procName = short
        return short
    }

    fun i(tag: String, msg: String) = record('I', tag, msg).also { Log.i(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable? = null) =
        record('W', tag, msg).also { Log.w(tag, msg, tr) }
    fun e(tag: String, msg: String, tr: Throwable? = null) =
        record('E', tag, msg).also { Log.e(tag, msg, tr) }

    private fun record(level: Char, tag: String, rawMsg: String) {
        val entry = Entry(System.currentTimeMillis(), level, proc(), tag, sanitize(rawMsg))
        synchronized(buf) {
            if (buf.size >= CAPACITY) buf.removeFirst()
            buf.addLast(entry)
        }
    }

    /** 写入侧脱敏：令牌值遮蔽、URI 载荷截断（仅对疑似行付正则代价） */
    private fun sanitize(msg: String): String {
        val lower = msg.lowercase()
        if (!lower.contains("token") && !lower.contains("uri") && !lower.contains("intent")) return msg
        var out = msg
        runCatching {
            out = out.replace(Regex("(?i)(token[=:])\\S+"), "$1***")
            out = out.replace(Regex("(?i)((?:intenturi|uri)[=:])\\S{28,}"), "$1<截断>")
        }
        return out
    }

    /** 快照（模块 App 日志页直读本进程缓冲） */
    fun snapshot(): List<Entry> = synchronized(buf) { buf.toList() }

    /** 拉取载荷：最近 [limit] 条的 JSON 数组字符串（跨进程广播 extra 用） */
    fun dumpJson(limit: Int = CAPACITY): String {
        val arr = JSONArray()
        synchronized(buf) { buf.takeLast(limit).forEach { arr.put(it.toJson()) } }
        return arr.toString()
    }
}
