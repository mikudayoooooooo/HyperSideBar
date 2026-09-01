package com.lsp.hypersidebar.util

import android.content.Context
import android.util.Log

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
 */
object DataLoader {

    private const val TAG = "DataLoader"
    private const val CACHE_TTL_MS = 30_000L
    private const val BACKSTOP_INTERVAL_MS = 5 * 60_000L

    @Volatile private var cachedResult: List<String>? = null
    @Volatile private var lastFetchTime = 0L
    @Volatile private var refreshing = false
    @Volatile private var backstopStarted = false
    @Volatile private var prewarmed = false

    // 连续失败计数（1C，PRD §9.4"推荐数据获取失败→toast"）：数据源是系统 API，
    // 连续失败通常=ROM 更新后反射签名失效（模块与该 ROM 根本不兼容的信号）——
    // 达 5 次且缓存仍为空时 toast 一次（进程生命周期内仅此一次，不重复打扰）
    @Volatile private var consecutiveFailures = 0
    @Volatile private var failureToastShown = false

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "FanDataLoader").apply { isDaemon = true }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun loadApps(context: Context): List<String> {
        val appContext = context.applicationContext
        startBackstop(appContext)
        val now = System.currentTimeMillis()
        if (cachedResult == null || (now - lastFetchTime) >= CACHE_TTL_MS) {
            refreshAsync(appContext)
        }
        return cachedResult ?: emptyList()
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
     */
    fun prewarmWithRetry(provider: () -> Context?, maxAttempts: Int = 12, intervalMs: Long = 5000L) {
        val task = object : Runnable {
            var attempt = 0
            override fun run() {
                if (prewarmed) return
                val ctx = runCatching { provider() }.getOrNull()?.applicationContext
                if (ctx != null) {
                    prewarmed = true
                    Log.i(TAG, "prewarm ok (attempt ${attempt + 1})")
                    prewarm(ctx)
                } else if (++attempt < maxAttempts) {
                    mainHandler.postDelayed(this, intervalMs)
                } else {
                    Log.w(TAG, "prewarm gave up after $maxAttempts attempts")
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
                    .onFailure { Log.w(TAG, "backstop dispatch failed: ${it.message}") }
                mainHandler.postDelayed(this, BACKSTOP_INTERVAL_MS)
            }
        }, BACKSTOP_INTERVAL_MS)
    }

    private fun refreshAsync(context: Context) {
        if (refreshing) return
        refreshing = true
        executor.execute {
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val suggestion = loadSuggestionApps(context)
                cachedResult = suggestion
                lastFetchTime = System.currentTimeMillis()
                consecutiveFailures = 0
                // label 预热（1C P3）：扇形呼出主线程逐 pkg 调 AppMetaCache.label，miss 即
                // PM binder（首呼出最多 14 次）——后台刷新顺带灌缓存，呼出路径恒命中
                suggestion.forEach { AppMetaCache.label(context, it) }
                Log.i(TAG, "refreshed ${suggestion.size} suggestion apps in ${android.os.SystemClock.elapsedRealtime() - t0}ms (background)")
            } catch (e: Throwable) {
                Log.w(TAG, "refresh failed: ${e.message}")
                consecutiveFailures++
                if (!failureToastShown && cachedResult == null && consecutiveFailures >= 5) {
                    failureToastShown = true
                    mainHandler.post {
                        runCatching {
                            android.widget.Toast.makeText(
                                context,
                                "推荐数据获取失败",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } finally {
                refreshing = false
            }
        }
    }

    /** 反射失败直接抛（1C：失败计数/兜底 toast 需要区分"拉取失败"与"合法空列表"）。 */
    private fun loadSuggestionApps(context: Context): List<String> {
        val cls = Class.forName("android.util.MiuiMultiWindowUtils")
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
