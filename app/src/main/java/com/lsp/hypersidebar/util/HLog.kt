package com.lsp.hypersidebar.util

import android.util.Log
import com.lsp.hypersidebar.prefs.HostPackages
import org.json.JSONArray
import org.json.JSONObject

/**
 * 进程内结构化日志环形缓冲（迭代六 §11.2）。
 *
 * 设计：四进程（launcher/ui/sys/模块 App）各持一份纯内存缓冲——写入是一次数组覆盖
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

    /**
     * 缓冲容量（2026-11-23 #13 后由 1000 提到 4000：issue #13 用户长时间使用后导出只剩
     * [sys] 138 条且手势流痕迹被 blank-tap 高频行稀释；4000 条 ≈600KB/进程，对
     * systemui/securitycenter 宿主进程无压力。分块回传（LogDumpBridge.CHUNK=200）
     * 天然兼容，只是块数变多）。
     */
    private const val CAPACITY = 4000

    /**
     * 关键行保护环（2026-11-23 #13 后新增）：E/W 级别 + 故障定位关键 tag 双写进一个
     * 小环（[KEEP_CAPACITY] 槽）。主环被高频 I 行（blank-tap/gesture end）滚掉后，
     * 故障时刻的关键行仍在这里——导出两环合并（主环在前、关键环补差），去重按 Entry 引用。
     * 纯内存追加，热路径只多一次 if + 偶尔一次小环覆盖，无 I/O。
     */
    private const val KEEP_CAPACITY = 300

    /**
     * 关键 tag 白名单（精确匹配，与各 hook 的 TAG 常量同源）：这些 tag 的 I 行即使主环
     * 被冲也保留。tag 选择原则——只收"故障定性必需"的行，不收高频明细：
     * SysInputSpy 的 gesture end（zone/dur/judge 摘要）、corner/AuthRelay（AUTH/回执）、
     * TurboLayout（触发带状态机）、AnchorResolver（拒装）、HSBNative（native live 回灌）、
     * LogDump 自身。blank-tap 等高频行不在名单。
     */
    private val KEEP_TAGS = setOf(
        "SysInputSpy", "AuthRelay", "TurboLayout", "AnchorResolver",
        "AnchorNative", "HSBNative", "LogDump", "LogCollector", "FanMenu",
    )

    private val keepBuf = ArrayDeque<Entry>(KEEP_CAPACITY)

    /**
     * 高频明细开关（volatile，无锁读）。赋值点：各进程 hook init 时读 prefs 一次 +
     * ConfigSync.applySync 收到全量配置时刷新（设置页切换 → 广播/pull → 三进程生效）。
     * 模块 App 进程内切换即时生效（SettingsPage 写 prefs 时同步置位）。
     */
    @Volatile
    var verboseEnabled: Boolean = false

    /** 线程安全的行时间格式化器（原每次 formatLine 都 new SimpleDateFormat） */
    private val LINE_TS_FMT: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    class Entry(
        val wallMs: Long,
        val level: Char,
        val proc: String,
        val tag: String,
        val msg: String,
    ) {
        fun formatLine(): String {
            val t = LINE_TS_FMT.format(
                java.time.Instant.ofEpochMilli(wallMs).atZone(java.time.ZoneId.systemDefault())
            )
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

    /** 淘汰计数（缓冲写满后每覆盖一条 +1）：判读"导出里缺某行"是没发生还是被滚掉
     *  （2026-10-04 P0-2：自检导出截断修复的配套——全量导出后仍需知道丢弃了多少） */
    @Volatile
    var dropped: Long = 0L
        private set

    private val buf = ArrayDeque<Entry>(CAPACITY)
    @Volatile private var procName: String? = null
    /** 权威进程名（XposedInit.onModuleLoaded 用 ModuleLoadedParam.getProcessName 播种，
     *  早于一切反射链可用时刻；1002 实机教训：极早期全链 null 时 "?" 被缓存到进程死） */
    @Volatile private var procRawOverride: String? = null

    fun setProcOverride(raw: String) {
        if (raw.isEmpty()) return
        procRawOverride = raw
        procName = null
    }

    /** 进程短名（首次解析后缓存）：launcher / ui / sys / app。
     *  解析顺序：override → Application.getProcessName（直读 cmdline）→
     *  ActivityThread 反射兜底；全链失败返回 "?" 且**不缓存**（下条日志重试）。 */
    fun proc(): String {
        procName?.let { return it }
        val raw = procRawOverride
            ?: runCatching { android.app.Application.getProcessName() }
                .getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                val clz = Class.forName("android.app.ActivityThread")
                clz.getMethod("currentProcessName").invoke(null) as? String
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return "?"
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
            if (buf.size >= CAPACITY) {
                buf.removeFirst()
                dropped++
            }
            buf.addLast(entry)
            // 关键行保护环双写（2026-11-23）：E/W 或关键 tag 的 I 行进小环，主环被冲也留底
            if (level != 'I' || tag in KEEP_TAGS) {
                if (keepBuf.size >= KEEP_CAPACITY) {
                    keepBuf.removeFirst()
                    keepDroppedCount++
                }
                keepBuf.addLast(entry)
            }
        }
    }

    /** 写入侧脱敏：令牌值遮蔽、URI 载荷截断（仅对疑似行付正则代价） */
    private fun sanitize(msg: String): String {
        // 逐条日志都调用；旧实现先 `lowercase()` 复制一份（1000 条环里每条一次分配），
        // 改用大小写无关的子串查找，零分配
        if (!msg.contains("token", ignoreCase = true) &&
            !msg.contains("uri", ignoreCase = true) &&
            !msg.contains("intent", ignoreCase = true)
        ) {
            return msg
        }
        var out = msg
        runCatching {
            out = out.replace(Regex("(?i)(token[=:])\\S+"), "$1***")
            out = out.replace(Regex("(?i)((?:intenturi|uri)[=:])\\S{28,}"), "$1<截断>")
        }
        return out
    }

    /** 快照（模块 App 日志页直读本进程缓冲）：主环 + 关键环补差（主环在前，按引用去重） */
    fun snapshot(): List<Entry> = synchronized(buf) {
        val seen = buf.toHashSet()
        val merged = ArrayList<Entry>(buf.size + keepBuf.size)
        merged.addAll(buf)
        keepBuf.forEach { if (it !in seen) merged.add(it) }
        merged.sortedBy { it.wallMs }
    }

    /** 拉取载荷：最近 [limit] 条的 JSON 数组字符串（跨进程广播 extra 用） */
    fun dumpJson(limit: Int = CAPACITY): String {
        val arr = JSONArray()
        snapshot().takeLast(limit).forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /** 关键环独立计数（meta 取证用：判读"关键行也被冲掉"还剩多少余量） */
    fun keepDropped(): Long = synchronized(buf) { keepDroppedCount }

    private var keepDroppedCount = 0L

    /** 测试探针（HLogKeepRingProbe 单源消费；生产代码不得调用） */
    internal fun capacityForTest(): Int = CAPACITY

    internal fun keepCapacityForTest(): Int = KEEP_CAPACITY
}
