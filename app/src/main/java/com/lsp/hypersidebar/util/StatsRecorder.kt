package com.lsp.hypersidebar.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lsp.hypersidebar.prefs.PrefsFiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * 使用数据记录（迭代六 §11.3，PRD §9.2 八项指标的采集与聚合）。
 *
 * 架构：launcher 与 :ui 各持一份进程内实例（object 随类加载器 per-process），
 * 事件写入内存聚合结构 + 防抖落盘宿主本地 prefs（DataLoader 落盘先例同路）；
 * 模块 App 经日志拉取同通道（LOG_DUMP 请求的 stats extra）取两份按天合并展示。
 *
 * 事件源（全部既有路径，零新 hook）：
 * - 手势停顿达标 → [onStall]（EdgeGestureHook/TurboLayout 状态机）
 * - 扇形展示成功 → [onFanShown]（FanMenuController.showInternal；含响应时间=
 *   停顿达标→装配完成、两次呼出间隔）
 * - 选中应用/全部应用/快捷方式 → [onOpen]/[onShortcut]（选中回调；含选择时长）
 * - 收起 → [onFanClosed]（doDismiss；区分"启动后自动退出"与"取消退出"）
 * - 启动机制结果 → [onLaunchResult]（launcher=BroadcastLaunchStrategy.onRelayResult；
 *   :ui=root 代发回告 ACTION_RELAY_RESULT；DirectLaunchStrategy 本地直启无结果
 *   回调，不产 launchOk/Fail——成功率口径=有确定性结果的启动）
 *
 * 误触率下线（0913）：现行定义判不准（"看了不用"被计入、误弹→关→重试链被豁免）；
 * recent 事件流（show/cancel…）继续采集，待明细数据攒够后重新定义上线。
 * 全部应用占比=allApps/opens。
 */
object StatsRecorder {

    /**
     * 计数键（0912 收口）：本对象写入 ↔ StatsPage 展示/CSV 导出读取的配对键，
     * 删键/改名只动这里。注意与 recent 事件类型（"show"/"open"/"cancel"…）是
     * 两套词表，勿混用。
     */
    object MetricKeys {
        const val SHOWS = "shows"
        const val OPENS = "opens"
        const val ALL_APPS = "allApps"
        const val SHORTCUTS = "shortcuts"
        const val CANCELS = "cancels"
        const val LAUNCH_OK = "launchOk"
        const val LAUNCH_FAIL = "launchFail"
    }

    private const val PREFS_NAME = PrefsFiles.STATS
    private const val KEY_STATS = "stats"
    private const val SAVE_DELAY_MS = 2000L
    private const val MAX_SAMPLES = 200
    private const val MAX_RECENT = 200
    private const val KEEP_DAYS = 30

    // ===== 内存聚合结构 =====
    private val dayCounters = LinkedHashMap<String, MutableMap<String, Int>>() // day→counters
    private val selectMs = ArrayDeque<Int>()
    private val responseMs = ArrayDeque<Int>()
    private val gapMs = ArrayDeque<Long>()
    private val recent = ArrayDeque<JSONObject>() // {ts,type,pkg?,ms?}

    @Volatile private var lastStallAt = 0L
    @Volatile private var lastShowAt = 0L
    @Volatile private var dirty = false
    @Volatile private var loaded = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    // ===== 事件入口（任意线程） =====

    /** 手势停顿达标（呼出确认），记录时间戳供响应时间计算 */
    fun onStall() {
        lastStallAt = SystemClock.elapsedRealtime()
    }

    /** 扇形展示成功（FanMenuController.showInternal 装配完成） */
    fun onFanShown() {
        val now = SystemClock.elapsedRealtime()
        val resp = now - lastStallAt
        if (lastStallAt in 1 until now && resp < 10_000) {
            pushSample(responseMs, resp.toInt())
        }
        val gap = now - lastShowAt
        if (lastShowAt in 1 until now && gap < 3_600_000) {
            pushSample(gapMs, gap)
        }
        lastShowAt = now
        bump(MetricKeys.SHOWS)
        addEvent("show")
        scheduleSave()
    }

    /** 选中并打开应用（全部应用入口 isAllApps=true）；selMs=展示→选中 */
    fun onOpen(pkg: String, isAllApps: Boolean, selMs: Int) {
        bump(MetricKeys.OPENS)
        if (isAllApps) bump(MetricKeys.ALL_APPS)
        pushSample(selectMs, selMs.coerceAtLeast(0))
        addEvent(if (isAllApps) "allApps" else "open", pkg, selMs)
        scheduleSave()
    }

    /** 选中快捷栏快捷方式（PRD §9.2 未单列，独立计数供观察） */
    fun onShortcut() {
        bump(MetricKeys.SHORTCUTS)
        addEvent("shortcut")
        scheduleSave()
    }

    /** 扇形收起：launched=false = 未选中取消退出（PRD"取消选中并退出的次数"） */
    fun onFanClosed(launched: Boolean) {
        if (!launched) {
            bump(MetricKeys.CANCELS)
            addEvent("cancel")
            scheduleSave()
        }
    }

    /** 有确定性结果的启动回告（alive/ok=true 计成功） */
    fun onLaunchResult(ok: Boolean) {
        bump(if (ok) MetricKeys.LAUNCH_OK else MetricKeys.LAUNCH_FAIL)
        addEvent(if (ok) "launchOk" else "launchFail")
        scheduleSave()
    }

    // ===== 展示侧读取 =====

    /** 今日计数器快照 */
    fun today(): Map<String, Int> {
        loadIfNeeded()
        synchronized(dayCounters) {
            return dayCounters[dayKey()]?.toMap() ?: emptyMap()
        }
    }

    /** 累计计数器（全历史天求和） */
    fun total(): Map<String, Int> {
        loadIfNeeded()
        val acc = mutableMapOf<String, Int>()
        synchronized(dayCounters) {
            dayCounters.values.forEach { day ->
                day.forEach { (k, v) -> acc[k] = (acc[k] ?: 0) + v }
            }
        }
        return acc
    }

    fun samples(): Triple<List<Int>, List<Int>, List<Long>> {
        loadIfNeeded()
        return synchronized(selectMs) { Triple(selectMs.toList(), synchronized(responseMs) { responseMs.toList() }, synchronized(gapMs) { gapMs.toList() }) }
    }

    // ===== 跨进程传输（配对 LogDumpBridge 的 stats extra） =====

    /** 拉取载荷：全量聚合结构 JSON（模块侧按天合并两进程） */
    fun dump(): String {
        loadIfNeeded()
        val root = JSONObject()
        synchronized(dayCounters) {
            val days = JSONObject()
            dayCounters.forEach { (d, c) ->
                val o = JSONObject()
                c.forEach { (k, v) -> o.put(k, v) }
                days.put(d, o)
            }
            root.put("days", days)
        }
        fun arr(list: Collection<*>) = JSONArray().apply { list.forEach { put(it) } }
        root.put("selectMs", arr(synchronized(selectMs) { selectMs.toList() }))
        root.put("responseMs", arr(synchronized(responseMs) { responseMs.toList() }))
        root.put("gapMs", arr(synchronized(gapMs) { gapMs.toList() }))
        root.put("recent", arr(synchronized(recent) { recent.toList() }.map { it.toString() }))
        return root.toString()
    }

    /** 模块侧合并多进程 dump（按天计数器求和，样本/recent 拼接） */
    fun mergeDumps(dumps: List<String>): JSONObject {
        val days = JSONObject()
        val select = mutableListOf<Int>()
        val resp = mutableListOf<Int>()
        val gap = mutableListOf<Long>()
        val recentAll = mutableListOf<Pair<Long, JSONObject>>()
        dumps.forEach { raw ->
            runCatching {
                val o = JSONObject(raw)
                o.optJSONObject("days")?.let { ds ->
                    ds.keys().forEach { d ->
                        val c = ds.getJSONObject(d)
                        val acc = JSONObject()
                        // 汇入已存在天
                        if (days.has(d)) {
                            val prev = days.getJSONObject(d)
                            prev.keys().forEach { k -> acc.put(k, prev.optInt(k)) }
                        }
                        c.keys().forEach { k -> acc.put(k, acc.optInt(k) + c.optInt(k)) }
                        days.put(d, acc)
                    }
                }
                o.optJSONArray("selectMs")?.let { a -> (0 until a.length()).forEach { select.add(a.optInt(it)) } }
                o.optJSONArray("responseMs")?.let { a -> (0 until a.length()).forEach { resp.add(a.optInt(it)) } }
                o.optJSONArray("gapMs")?.let { a -> (0 until a.length()).forEach { gap.add(a.optLong(it)) } }
                o.optJSONArray("recent")?.let { a ->
                    (0 until a.length()).forEach {
                        runCatching { val e = JSONObject(a.optString(it)); recentAll.add(e.optLong("ts") to e) }
                    }
                }
            }
        }
        return JSONObject()
            .put("days", days)
            .put("selectMs", JSONArray(select))
            .put("responseMs", JSONArray(resp))
            .put("gapMs", JSONArray(gap))
            .put("recent", JSONArray(recentAll.sortedBy { it.first }.map { it.second }))
    }

    // ===== 内部 =====

    /** 当天键（StatsPage 合并 dump 取今日计数同用——日期格式此前两处各写一份） */
    fun dayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())

    private fun bump(key: String) {
        loadIfNeeded()
        synchronized(dayCounters) {
            dayCounters.getOrPut(dayKey()) { mutableMapOf() }
                .merge(key, 1, Int::plus)
        }
    }

    private fun <T> pushSample(q: ArrayDeque<T>, v: T) {
        synchronized(q) {
            if (q.size >= MAX_SAMPLES) q.removeFirst()
            q.addLast(v)
        }
    }

    private fun addEvent(type: String, pkg: String? = null, ms: Int? = null) {
        val e = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("type", type)
            pkg?.let { put("pkg", it) }
            ms?.let { put("ms", it) }
        }
        synchronized(recent) {
            if (recent.size >= MAX_RECENT) recent.removeFirst()
            recent.addLast(e)
        }
    }

    private fun scheduleSave() {
        if (!dirty) {
            dirty = true
            mainHandler.postDelayed(saveRunnable, SAVE_DELAY_MS)
        }
    }

    private fun saveNow() {
        dirty = false
        // 裁剪老天
        synchronized(dayCounters) {
            val cutoff = (System.currentTimeMillis() / 86_400_000L) - KEEP_DAYS
            dayCounters.keys.removeAll { d ->
                runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { isLenient = false }
                        .parse(d)?.time?.div(86_400_000L)?.let { it < cutoff } ?: true
                }.getOrDefault(false)
            }
        }
        runCatching {
            contextRef?.applicationContext
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_STATS, dump())?.apply()
        }.onFailure { HLog.w("Stats", "save failed: ${it.message}") }
    }

    @Volatile private var contextRef: Context? = null

    private fun loadIfNeeded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val ctx = contextRef ?: return
            runCatching {
                ctx.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_STATS, null)
            }.getOrNull()?.let { raw -> hydrate(raw) }
        }
    }

    private fun hydrate(raw: String) {
        runCatching {
            val o = JSONObject(raw)
            o.optJSONObject("days")?.let { ds ->
                synchronized(dayCounters) {
                    ds.keys().forEach { d ->
                        val c = ds.getJSONObject(d)
                        val m = dayCounters.getOrPut(d) { mutableMapOf() }
                        c.keys().forEach { k -> m[k] = c.optInt(k) }
                    }
                }
            }
            o.optJSONArray("selectMs")?.let { a -> (0 until a.length()).forEach { selectMs.addLast(a.optInt(it)) } }
            o.optJSONArray("responseMs")?.let { a -> (0 until a.length()).forEach { responseMs.addLast(a.optInt(it)) } }
            o.optJSONArray("gapMs")?.let { a -> (0 until a.length()).forEach { gapMs.addLast(a.optLong(it)) } }
            o.optJSONArray("recent")?.let { a ->
                (0 until a.length()).forEach { runCatching { recent.addLast(JSONObject(a.optString(it))) } }
            }
        }.onFailure { HLog.w("Stats", "hydrate failed: ${it.message}") }
    }

    /** hook 进程 init 时注入宿主 context（落盘用；不注入=纯内存模式） */
    fun init(context: Context) {
        contextRef = context.applicationContext
        loadIfNeeded()
    }
}
