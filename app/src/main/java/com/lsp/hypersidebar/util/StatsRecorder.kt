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
 * - 选中应用/全部应用 → [onOpen]（选中回调；含选择时长）；快捷方式 → [onShortcut]
 * - 收起 → [onFanClosed]（doDismiss 的 cause：user_up / launched / watchdog / preempted）
 * - 启动机制结果 → [onLaunchResult]（launcher=BroadcastLaunchStrategy.onRelayResult；
 *   :ui 的 DirectLaunchStrategy 本地直启无结果回调，不产 launchOk/Fail——成功率口径=有确定性结果的启动）
 *
 * 全部应用占比=allApps/opens。
 *
 * 误触率：0912 曾因"按天计数器算不出逐事件口径"砍掉过一次；口径 v2（PRD §9.3 定稿）
 * 落地方式改为**逐事件环形缓冲 + 展示侧纯函数判定**（[MisclickCaliber]），本类只存事实、
 * 不算分层，避免计数器与事件流水两套真值。§9.2 原八项计数器语义全部保持不变。
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
    private const val KEEP_DAYS = 30

    /** 逐事件环形缓冲上限（PRD §11.3 实现假设 500；紧凑字符串落盘约 10KB 量级） */
    private const val MAX_EVENTS = 500

    /** 线程安全的日期格式化器（原每调用 new SimpleDateFormat，热路径重复构造） */
    private val DAY_FMT = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

    // ===== 内存聚合结构 =====
    private val dayCounters = LinkedHashMap<String, MutableMap<String, Int>>() // day→counters
    private val selectMs = ArrayDeque<Int>()
    private val responseMs = ArrayDeque<Int>()
    private val gapMs = ArrayDeque<Long>()

    @Volatile private var lastStallAt = 0L
    @Volatile private var lastShowAt = 0L
    @Volatile private var dirty = false
    @Volatile private var loaded = false

    // ===== 逐事件流水（§11.3 采集层 v2）=====
    // 误触口径 v2 要的是"每次展开"的六元组，按天计数器算不出来（0912 因此砍过一次）。
    // 分层/修正档/无效链一律在展示侧由 MisclickCaliber 纯函数算，此处只存事实——
    // 不落 30 个按天 tier 键，避免两套真值互相漂移。

    /** 进行中的一次展开（show→收起）；UP 之前的所有字段都往这里累积 */
    private class OpenExpand {
        @Volatile var trace = 0L
        @Volatile var channel = FanChannel.EDGE
        @Volatile var showAt = 0L
        @Volatile var wallShowAt = 0L
        @Volatile var upAt = 0L
        @Volatile var preselected = false
        @Volatile var zone = CancelZone.NONE
        @Volatile var travel = 0f
        @Volatile var deadZonePx = 0f
        @Volatile var lastX = 0f
        @Volatile var lastY = 0f
        @Volatile var hasLast = false
        @Volatile var live = false
    }

    private val open = OpenExpand()
    private val events = ArrayDeque<ExpandEvent>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    // ===== 事件入口（任意线程） =====

    /** 手势停顿达标（呼出确认），记录时间戳供响应时间计算 */
    fun onStall() {
        lastStallAt = SystemClock.elapsedRealtime()
    }

    /** 扇形展示成功（FanMenuController.showInternal 装配完成）；通道=竖屏边缘/横屏热区/底角 */
    fun onFanShown(channel: FanChannel) {
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
        synchronized(open) {
            open.trace += 1
            open.channel = channel
            open.showAt = now
            open.wallShowAt = System.currentTimeMillis()
            open.upAt = 0L
            open.preselected = false
            open.zone = CancelZone.NONE
            open.travel = 0f
            open.deadZonePx = 0f
            open.hasLast = false
            open.live = true
        }
        scheduleSave()
    }

    /** 进入预选（host 的 selectedSince 起算点）——误触判定的主判据 */
    fun onFanPreselected() {
        synchronized(open) { if (open.live) open.preselected = true }
    }

    /**
     * 展示期间的 MOVE：累加指尖位移（一次 sqrt，热路径零分配）。
     * 位移只派生"原位/划过"标签，不进判定。
     */
    fun onFanMove(x: Float, y: Float) {
        synchronized(open) {
            if (!open.live) return
            if (open.hasLast) {
                val dx = x - open.lastX
                val dy = y - open.lastY
                open.travel += kotlin.math.sqrt(dx * dx + dy * dy)
            }
            open.lastX = x
            open.lastY = y
            open.hasLast = true
        }
    }

    /** 松手（host UP）：记松手时刻 + 取消区；deadZonePx 供位移分档 */
    fun onFanUp(zone: CancelZone, deadZonePx: Float) {
        synchronized(open) {
            if (!open.live) return
            if (open.upAt == 0L) open.upAt = SystemClock.elapsedRealtime()
            open.zone = zone
            open.deadZonePx = deadZonePx
        }
    }

    /** 收起：六元组定格入环形缓冲（UP 未记到的路径以 upAt=0 落库，判定自然归 NOT_CANCEL） */
    fun onFanClosed(cause: DismissCause) {
        val ev = synchronized(open) {
            if (!open.live) return
            open.live = false
            ExpandEvent(
                traceId = open.trace,
                channel = open.channel,
                showAt = open.showAt,
                upAt = open.upAt,
                preselected = open.preselected,
                zone = open.zone,
                cause = cause,
                travelPx = open.travel,
                deadZonePx = open.deadZonePx,
                wallShowAt = open.wallShowAt
            )
        }
        synchronized(events) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(ev)
        }
        // §9.2 原「取消退出次数」口径不变：非启动收尾即计一次
        if (cause != DismissCause.LAUNCHED) bump(MetricKeys.CANCELS)
        scheduleSave()
    }

    /** 逐事件流水（展示侧读取；已按展开时刻升序） */
    fun eventLog(): List<ExpandEvent> = synchronized(events) { events.toList() }

    /** 从合并后的 dump 里取逐事件流水（模块 App 侧本进程无事件，数据全在两宿主回传里） */
    fun eventsOf(merged: JSONObject): List<ExpandEvent> =
        merged.optJSONArray("events")?.let { a ->
            (0 until a.length()).mapNotNull { decodeEvent(a.optString(it)) }
        } ?: emptyList()

    /** 逐事件的 CSV 明细行（导出用；口径展示与判定共用同一份字段序） */
    fun eventCsvRows(list: List<ExpandEvent>): String {
        val sb = StringBuilder("trace,channel,wall_show_at,hold_ms,preselected,zone,cause,travel_px,dead_zone_px\n")
        list.forEach { e ->
            sb.append(e.traceId).append(',')
                .append(e.channel).append(',')
                .append(e.wallShowAt).append(',')
                .append(e.holdMs).append(',')
                .append(if (e.preselected) 1 else 0).append(',')
                .append(e.zone).append(',')
                .append(e.cause).append(',')
                .append(e.travelPx.toInt()).append(',')
                .append(e.deadZonePx.toInt()).append('\n')
        }
        return sb.toString()
    }

    /** 选中并打开应用（全部应用入口 isAllApps=true）；selMs=展示→选中 */
    fun onOpen(isAllApps: Boolean, selMs: Int) {
        bump(MetricKeys.OPENS)
        if (isAllApps) bump(MetricKeys.ALL_APPS)
        pushSample(selectMs, selMs.coerceAtLeast(0))
        scheduleSave()
    }

    /** 选中快捷栏快捷方式（PRD §9.2 未单列，独立计数供观察） */
    fun onShortcut() {
        bump(MetricKeys.SHORTCUTS)
        scheduleSave()
    }

    /** 有确定性结果的启动回告（alive/ok=true 计成功） */
    fun onLaunchResult(ok: Boolean) {
        bump(if (ok) MetricKeys.LAUNCH_OK else MetricKeys.LAUNCH_FAIL)
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

    private fun encodeEvent(e: ExpandEvent) =
        "${e.traceId},${e.channel.ordinal},${e.showAt},${e.upAt},${if (e.preselected) 1 else 0}," +
            "${e.zone.ordinal},${e.cause.ordinal},${e.travelPx.toInt()},${e.deadZonePx.toInt()},${e.wallShowAt}"

    private fun decodeEvent(s: String): ExpandEvent? = runCatching {
        val f = s.split(',')
        ExpandEvent(
            traceId = f[0].toLong(),
            channel = FanChannel.entries[f[1].toInt()],
            showAt = f[2].toLong(),
            upAt = f[3].toLong(),
            preselected = f[4] == "1",
            zone = CancelZone.entries[f[5].toInt()],
            cause = DismissCause.entries[f[6].toInt()],
            travelPx = f[7].toFloat(),
            deadZonePx = f[8].toFloat(),
            wallShowAt = f[9].toLong()
        )
    }.getOrNull()

    private fun eventsArray(list: List<ExpandEvent>) =
        JSONArray().apply { list.forEach { put(encodeEvent(it)) } }

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
        root.put("events", eventsArray(eventLog()))
        return root.toString()
    }

    /** 模块侧合并多进程 dump（按天计数器求和，样本拼接） */
    fun mergeDumps(dumps: List<String>): JSONObject {
        val days = JSONObject()
        val select = mutableListOf<Int>()
        val resp = mutableListOf<Int>()
        val gap = mutableListOf<Long>()
        val evs = mutableListOf<ExpandEvent>()
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
                // elapsedRealtime 是设备级时钟，跨进程直接按展开时刻归并即可
                o.optJSONArray("events")?.let { a ->
                    (0 until a.length()).forEach { i -> decodeEvent(a.optString(i))?.let(evs::add) }
                }
            }
        }
        return JSONObject()
            .put("days", days)
            .put("selectMs", JSONArray(select))
            .put("responseMs", JSONArray(resp))
            .put("gapMs", JSONArray(gap))
            .put("events", eventsArray(evs.sortedBy { it.showAt }.takeLast(MAX_EVENTS)))
    }

    // ===== 内部 =====

    /** 当天键（StatsPage 合并 dump 取今日计数同用——日期格式此前两处各写一份） */
    fun dayKey(): String = java.time.LocalDate.now().format(DAY_FMT)

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
            val cutoff = java.time.LocalDate.now().toEpochDay() - KEEP_DAYS
            dayCounters.keys.removeAll { d ->
                runCatching { java.time.LocalDate.parse(d).toEpochDay() < cutoff }
                    .getOrDefault(false)
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
            o.optJSONArray("events")?.let { a ->
                val persisted = (0 until a.length()).mapNotNull { decodeEvent(a.optString(it)) }
                synchronized(events) {
                    events.clear()
                    persisted.takeLast(MAX_EVENTS).forEach { events.addLast(it) }
                }
            }
        }.onFailure { HLog.w("Stats", "hydrate failed: ${it.message}") }
    }

    /** hook 进程 init 时注入宿主 context（落盘用；不注入=纯内存模式） */
    fun init(context: Context) {
        contextRef = context.applicationContext
        loadIfNeeded()
    }
}
