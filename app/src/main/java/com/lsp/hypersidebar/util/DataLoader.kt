package com.lsp.hypersidebar.util

import android.content.Context
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefsFiles
import com.lsp.hypersidebar.util.HLog

/**
 * 扇形菜单数据源（2026-08-25 决策：provider 整条路径退役）。
 *
 * 可打开应用统一取自系统准入列表 `MiuiMultiWindowUtils.getFreeformSuggestionList`
 * （即 PRD §3.2 准入规则的产物——无小窗资格的应用天然不在其中，无需二次过滤）。
 * 快捷栏数据（用户 shortcut_actions）不经此类（Phase 3 接入）。
 *
 * 缓存策略（三层，1B 增补周期兜底）：反射调用实测 ~0.7-1s，绝不允许出现在呼出关键路径。
 * - 惰性层：[loadApps] 永远同步返回缓存（冷缓存时为空，扇形只显示用户固定应用，推荐位
 *   下次呼出补齐，PRD"用户固定优先"），距上次成功拉取 ≥30s 触发后台刷新；
 * - 预热层：hook init 调 [prewarm]，首呼出大概率命中缓存（修 S6：停顿→扇形可见
 *   间歇性 ~950ms 超标）；
 * - 周期兜底层：每 [BACKSTOP_INTERVAL_MS] 自续排后台刷新（1B 2026-08-30）——消灭
 *   "长期闲置后首呼出显示旧数据"（惰性层只惠及下一次呼出）与"prewarm 因 context
 *   未就绪被跳过后永不刷新"（[loadApps] 也会补启循环）。数据源为使用习惯驱动的
 *   系统建议列表，5 分钟远超其真实变化速度；成本 ~1s 后台 binder/次，常驻进程可忽略。
 *   周期挂主线程 handler（仅 postDelayed 微秒级），刷新在后台 executor（[refreshing]
 *   标志防重入，fixed-rate 循环安全）。
 * - 落盘持久层（迭代四 §1.2，修冷启动推荐竞态窗）：每次成功刷新把列表 JSON 写宿主进程
 *   本地 prefs（hook 进程 remotePrefs 只读不适用；列表没变化不写、空列表不覆盖）；
 *   首次刷新前先从盘同步灌缓存（后台线程，不设 lastFetchTime→TTL 视为过期必刷）。
 *   进程重启后首呼出即显示上次会话的推荐，竞态窗（冷 binder ~3.5s）实际消失。
 */
object DataLoader {

    private const val TAG = "DataLoader"
    private const val CACHE_TTL_MS = 30_000L
    private const val BACKSTOP_INTERVAL_MS = 5 * 60_000L
    private const val DISK_PREFS_NAME = PrefsFiles.DATA
    private const val DISK_KEY_SUGGESTIONS = "suggestions"
    /** v2 分数表落盘键（JSON 对象 pkg→score）；旧 DISK_KEY_SUGGESTIONS 数组只为升级兼容读 */
    private const val DISK_KEY_SCORES = "suggestion_scores"
    /** 兜底 legacy 序上限（与今日扇形可见量级一致；纯防御，正常远小于此） */
    private const val LEGACY_ORDER_LIMIT = 64

    // ===== 原料仓（2026-10-24 计划 §A2：原料（分数/上下文）与成品（有序列表）分离，
    // 成品每次呼出按当前来源在 [rankedApps] 现算——纯内存查表，呼出热路径零 binder）=====
    /** ROM 分数表；null=未就绪（新反射路径未成功过）⇒ rankedApps 走 legacyOrder */
    @Volatile private var romScores: Map<String, Int>? = null
    /** 上下文计数表 source→target→count（ContextLaunchLog 剪枝后）；空=无信号 */
    @Volatile private var contextCounts: Map<String, Map<String, Int>> = emptyMap()
    /** 降级序：旧路径产物或旧盘数据（无分数），= 今日行为 */
    @Volatile private var legacyOrder: List<String> = emptyList()

    /**
     * 数据源死亡回调（迭代四 §1.3，PRD §9.4 用户加强：获取失败→取消所有 hook、
     * 扇形不再展示）：连续失败 ≥[DEAD_THRESHOLD] 次且缓存仍空（含盘）=ROM 不兼容信号。
     * hook 进程 init 时注册（注册后恰触发一次）；模块进程不注册，无副作用。
     */
    @Volatile var onDataSourceDead: (() -> Unit)? = null

    @Volatile private var cachedResult: List<String>? = null
    @Volatile private var lastFetchTime = 0L
    @Volatile private var refreshing = false
    @Volatile private var backstopStarted = false
    @Volatile private var prewarmed = false
    @Volatile private var hydrated = false
    @Volatile private var lastPersistedJson: String? = null

    // 连续失败计数（1C，PRD §9.4"推荐数据获取失败→toast"）：数据源是系统 API，
    // 连续失败通常=ROM 更新后反射签名失效（模块与该 ROM 根本不兼容的信号）——
    // 达 5 次且缓存仍为空（含盘，落盘层灌入也算"有过数据"）时 toast 一次并触发
    // [onDataSourceDead]（进程生命周期内至多一次；成功即清零且缓存非空后不再触发）
    @Volatile private var consecutiveFailures = 0
    @Volatile private var failureToastShown = false

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "FanDataLoader").apply { isDaemon = true }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())


    /** 热重载释放（2026-10-18）：旧代的线程池跨代存活（守护线程不会自己退），
     *  钉死旧 classloader；新代是全新 object 自带新池。未起过 = 空操作。 */
    fun releaseForReload() {
        runCatching { executor.shutdownNow() }
            .onFailure { HLog.w(TAG, "executor shutdown failed: ${it.message}") }
        runCatching { HLog.i(TAG, "releaseForReload: done") }
    }
    fun loadApps(context: Context): List<String> {
        val appContext = context.applicationContext
        startBackstop(appContext)
        val now = System.currentTimeMillis()
        if (cachedResult == null || (now - lastFetchTime) >= CACHE_TTL_MS) {
            refreshAsync(appContext)
        }
        return cachedResult ?: emptyList()
    }

    /**
     * 单一出口（计划 §A2）：按当前来源排序的推荐列表。
     * 分数未就绪 ⇒ 返回 legacyOrder（= 今日行为，冷启动首呼出与旧版逐字节一致）；
     * 就绪 ⇒ SuggestionRanker 条件加权（source null/未知 = 纯 ROM 序）。
     * 纯内存查表，呼出热路径零 binder。
     */
    fun rankedApps(context: Context, source: String?): List<String> {
        val appContext = context.applicationContext
        startBackstop(appContext)
        val now = System.currentTimeMillis()
        if (romScores == null || (now - lastFetchTime) >= CACHE_TTL_MS) {
            refreshAsync(appContext)
        }
        val scores = romScores ?: return legacyOrder
        return SuggestionRanker.rank(
            scores, contextCounts, source,
            com.lsp.hypersidebar.prefs.LayoutDefaults.REC_CTX_WEIGHT,
            com.lsp.hypersidebar.prefs.LayoutDefaults.REC_CTX_CAP
        )
    }

    /**
     * 上下文记录落库后的本进程同步（:ui 写读同进程零延迟；launcher 侧靠周期刷新）。
     * 由 FreeformLauncher 记录点调用（C3），本提交先立接口。
     */
    fun updateContextCounts(counts: Map<String, Map<String, Int>>) {
        contextCounts = counts
    }

    /** hook init 时调用：进程存活期间提前把推荐列表拉进缓存，并启动周期兜底循环。 */
    fun prewarm(context: Context) {
        val appContext = context.applicationContext
        refreshAsync(appContext)
        startBackstop(appContext)
    }

    /**
     * 带重试的预热（修"首次呼出只有固定应用"）：hook init 时宿主 appContext 可能未就绪
     * （launcher 实测直接抛 NPE，prewarm skipped），首轮呼出必然冷缓存。此方法每 intervalMs
     * 重试 provider 直到拿到上下文或耗尽次数，成功即 prewarm（刷新+兜底循环）。
     * [onReady] = 上下文就绪后的追加动作（后台线程回调，如图标预灌），失败不影响预热本体。
     */
    fun prewarmWithRetry(
        provider: () -> Context?,
        maxAttempts: Int = 12,
        intervalMs: Long = 5000L,
        onReady: ((Context) -> Unit)? = null
    ) {
        val task = object : Runnable {
            var attempt = 0
            override fun run() {
                if (prewarmed) return
                val ctx = runCatching { provider() }.getOrNull()?.applicationContext
                if (ctx != null) {
                    prewarmed = true
                    HLog.i(TAG, "prewarm ok (attempt ${attempt + 1})")
                    prewarm(ctx)
                    if (onReady != null) {
                        executor.execute {
                            runCatching { onReady(ctx) }
                                .onFailure { HLog.w(TAG, "prewarm onReady failed: ${it.message}") }
                        }
                    }
                } else if (++attempt < maxAttempts) {
                    mainHandler.postDelayed(this, intervalMs)
                } else {
                    HLog.w(TAG, "prewarm gave up after $maxAttempts attempts")
                }
            }
        }
        mainHandler.post(task)
    }

    /**
     * 周期兜底循环（每进程一次）：自续排而非 fixed-rate 定时器框架，每轮独立容错——
     * 单次失败不杀循环。挂主线程 handler，触发成本微秒级；实际刷新走后台 executor。
     */
    private fun startBackstop(appContext: Context) {
        if (backstopStarted) return
        backstopStarted = true
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                runCatching { refreshAsync(appContext) }
                    .onFailure { HLog.w(TAG, "backstop dispatch failed: ${it.message}") }
                mainHandler.postDelayed(this, BACKSTOP_INTERVAL_MS)
            }
        }, BACKSTOP_INTERVAL_MS)
    }

    private fun refreshAsync(context: Context) {
        if (refreshing) return
        refreshing = true
        executor.execute {
            try {
                hydrateFromDisk(context)
                val t0 = android.os.SystemClock.elapsedRealtime()
                // 原料仓刷新（§A2）：新路径拿 romScores；任一环异常降级旧路径=legacyOrder
                val scores = runCatching { loadRomScores(context) }
                    .onFailure { HLog.w(TAG, "romScores path failed, fallback legacy: ${it.message}") }
                    .getOrNull()
                // 上下文记录装载（搭现有周期车，不新增线程/循环）：读+剪枝进内存
                runCatching {
                    val now = System.currentTimeMillis()
                    contextCounts = ContextLaunchLog.countsAll(
                        ContextLaunchLog.prune(ContextLaunchLog.read(context.contentResolver), now)
                    )
                }.onFailure { HLog.w(TAG, "context load failed: ${it.message}") }

                val suggestion: List<String>
                if (scores != null) {
                    romScores = scores
                    suggestion = SuggestionRanker.rank(
                        scores, contextCounts, null,
                        com.lsp.hypersidebar.prefs.LayoutDefaults.REC_CTX_WEIGHT,
                        com.lsp.hypersidebar.prefs.LayoutDefaults.REC_CTX_CAP
                    )
                } else {
                    suggestion = loadSuggestionApps(context)
                    legacyOrder = suggestion
                }
                cachedResult = suggestion
                lastFetchTime = System.currentTimeMillis()
                consecutiveFailures = 0
                persistToDisk(context, scores, suggestion)
                // label 预热（1C P3）：扇形呼出主线程逐 pkg 调 AppMetaCache.label，miss 即
                // PM binder（首呼出最多 14 次）——后台刷新顺带灌缓存，呼出路径恒命中
                suggestion.take(LEGACY_ORDER_LIMIT).forEach { AppMetaCache.label(context, it) }
                HLog.i(
                    TAG,
                    "refreshed ${suggestion.size} suggestion apps in ${android.os.SystemClock.elapsedRealtime() - t0}ms (background, scores=${scores != null}, ctx=${contextCounts.size})"
                )
            } catch (e: Throwable) {
                HLog.w(TAG, "refresh failed: ${e.message}")
                consecutiveFailures++
                if (!failureToastShown && cachedResult == null && consecutiveFailures >= DEAD_THRESHOLD) {
                    failureToastShown = true
                    mainHandler.post {
                        toastOnMain(context, "推荐数据获取失败")
                        onDataSourceDead?.invoke()
                    }
                }
            } finally {
                refreshing = false
            }
        }
    }

    /** 数据源死亡阈值（同机制熔断口径；缓存含盘灌入，"从未有过数据"才计） */
    private const val DEAD_THRESHOLD = 5

    /**
     * 盘灌缓存（迭代四 §1.2，C2 迁移）：优先 v2 分数表；旧数组键升级兼容——
     * 只灌 legacyOrder/cachedResult（无分数），不设 lastFetchTime（TTL 过期必刷）。
     * executor 单线程调用，无并发。
     */
    private fun hydrateFromDisk(context: Context) {
        if (hydrated) return
        hydrated = true
        if (cachedResult != null) return
        runCatching {
            val prefs = context.applicationContext
                .getSharedPreferences(DISK_PREFS_NAME, Context.MODE_PRIVATE)
            val scoresJson = prefs.getString(DISK_KEY_SCORES, null)
            val scores = RomSignals.parsePersistedScores(scoresJson)
            if (scores != null && scores.isNotEmpty()) {
                romScores = scores
                val list = SuggestionRanker.rank(
                    scores, emptyMap(), null,
                    com.lsp.hypersidebar.prefs.LayoutDefaults.REC_CTX_WEIGHT,
                    com.lsp.hypersidebar.prefs.LayoutDefaults.REC_CTX_CAP
                )
                cachedResult = list
                lastPersistedJson = scoresJson
                HLog.i(TAG, "hydrated ${list.size} scores from disk (stale, refresh pending)")
                return
            }
            val json = prefs.getString(DISK_KEY_SUGGESTIONS, null)
            if (json != null) {
                lastPersistedJson = json
                val list = RomSignals.parsePersistedLegacy(json)
                if (list.isNotEmpty()) {
                    legacyOrder = list
                    cachedResult = list
                    HLog.i(TAG, "hydrated ${list.size} suggestions from disk (legacy, stale, refresh pending)")
                }
            }
        }.onFailure { HLog.w(TAG, "hydrate failed: ${it.message}") }
    }

    /**
     * 成功刷新落盘（C2 迁移）：分数路径写 v2 对象键（pkg→score）；legacy 路径沿用旧
     * 数组键。列表没变不写（推荐列表日常稳定，实际写盘趋近于零）；空列表不覆盖。
     */
    private fun persistToDisk(context: Context, scores: Map<String, Int>?, list: List<String>) {
        if (list.isEmpty()) return
        val json = if (scores != null) {
            RomSignals.serializePersistedScores(scores)
        } else {
            org.json.JSONArray(list).toString()
        }
        val key = if (scores != null) DISK_KEY_SCORES else DISK_KEY_SUGGESTIONS
        if (json == lastPersistedJson) return
        lastPersistedJson = json
        runCatching {
            context.applicationContext
                .getSharedPreferences(DISK_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(key, json).apply()
        }.onFailure { HLog.w(TAG, "persist failed: ${it.message}") }
    }

    /**
     * 新反射路径（计划 §A2，三代反编译实证）：黑名单 → 只读解析 freeform_timestamps
     * （双形态兼容+内存剪枝，**绝不写回**）→ 精确 HashMap 形参 → calFreeformSuggestionList
     * → 分数表。任一环异常向上抛，由 refreshAsync 降级 legacy 路径。
     *
     * 为什么不直接调 getFreeformSuggestionList：它内部丢分数只回包名（§0
     * `MiuiMultiWindowUtils.java:2561-2581`），条件加权需要分数本体。
     */
    private fun loadRomScores(context: Context): Map<String, Int> {
        val utilsCls = Class.forName(FreeformLauncher.MIUI_MULTI_WINDOW_UTILS)
        // ① 黑名单（必须真取：传错会经 calFreeformSuggestionList 污染进程级
        //    sFreeformPackageNotSupport——该缓存与 ROM 自用代码共享）
        val blacklist = runCatching {
            val m = utilsCls.getMethod("getFreeformBlackList", Context::class.java)
            @Suppress("UNCHECKED_CAST")
            m.invoke(null, context) as List<String>
        }.getOrElse {
            val m = utilsCls.getDeclaredMethod("getFreeformBlackList", Context::class.java).apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            m.invoke(null, context) as List<String>
        }
        // ② 时间戳只读解析（String 元素实锤 + Object 防御双形态；内存剪枝语义照 ROM）
        val timestampsJson = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, "freeform_timestamps"
            )
        }.getOrNull()
        val timestamps = RomSignals.parseFreeformTimestamps(timestampsJson, System.currentTimeMillis())
        // ③ 计分（精确签名：getMethod 须 HashMap::class.java，invoke 传子类实例合法）
        val adapterCls = Class.forName(FreeformLauncher.MIUI_MULTI_WINDOW_ADAPTER)
        val calc = adapterCls.getMethod(
            "calFreeformSuggestionList",
            Context::class.java, List::class.java, HashMap::class.java
        )
        @Suppress("UNCHECKED_CAST")
        val raw = calc.invoke(null, context, blacklist, timestamps) as? Map<String, Int>
            ?: throw IllegalStateException("calFreeformSuggestionList returned non-map")
        // 只留非负分数（防御 ROM 侧异常值）；准入判定已在 ROM 内完成
        return raw.filterValues { it >= 0 }
    }

    /** 反射失败直接抛（1C：失败计数/兜底 toast 需要区分"拉取失败"与"合法空列表"）。 */
    private fun loadSuggestionApps(context: Context): List<String> {
        val cls = Class.forName(FreeformLauncher.MIUI_MULTI_WINDOW_UTILS)
        val method = try {
            cls.getMethod("getFreeformSuggestionList", Context::class.java)
        } catch (_: NoSuchMethodException) {
            cls.getDeclaredMethod("getFreeformSuggestionList", Context::class.java).apply {
                isAccessible = true
            }
        }
        @Suppress("UNCHECKED_CAST")
        val rawList = method.invoke(null, context) as? List<String>
            ?: throw IllegalStateException("getFreeformSuggestionList returned ${method.returnType}")
        return rawList.map { it.split(",,").first() }
    }
}
