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
 * 缓存策略（1A 实测修订）：反射调用实测 ~0.7-1s，绝不允许出现在呼出关键路径——
 * [loadApps] 永远同步返回缓存（冷缓存时为空，扇形只显示用户固定应用，推荐位下次呼出
 * 补齐，PRD"用户固定优先"），过期由后台线程刷新；hook init 调 [prewarm] 预热，
 * 首呼出大概率已命中缓存（修 S6：停顿→扇形可见间歇性 ~950ms 超标）。
 */
object DataLoader {

    private const val TAG = "DataLoader"
    private const val CACHE_TTL_MS = 30_000L

    @Volatile private var cachedResult: List<String>? = null
    @Volatile private var lastFetchTime = 0L
    @Volatile private var refreshing = false

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "FanDataLoader").apply { isDaemon = true }
    }

    fun loadApps(context: Context): List<String> {
        val now = System.currentTimeMillis()
        if (cachedResult == null || (now - lastFetchTime) >= CACHE_TTL_MS) {
            refreshAsync(context)
        }
        return cachedResult ?: emptyList()
    }

    /** hook init 时调用：进程存活期间提前把推荐列表拉进缓存。 */
    fun prewarm(context: Context) {
        refreshAsync(context)
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
                Log.i(TAG, "refreshed ${suggestion.size} suggestion apps in ${android.os.SystemClock.elapsedRealtime() - t0}ms (background)")
            } catch (e: Throwable) {
                Log.w(TAG, "refresh failed: ${e.message}")
            } finally {
                refreshing = false
            }
        }
    }

    private fun loadSuggestionApps(context: Context): List<String> {
        return try {
            val cls = Class.forName("android.util.MiuiMultiWindowUtils")
            val method = try {
                cls.getMethod("getFreeformSuggestionList", Context::class.java)
            } catch (_: NoSuchMethodException) {
                cls.getDeclaredMethod("getFreeformSuggestionList", Context::class.java).apply {
                    isAccessible = true
                }
            }
            @Suppress("UNCHECKED_CAST")
            val rawList = method.invoke(null, context) as? List<String> ?: emptyList()
            rawList.map { it.split(",,").first() }
        } catch (e: Throwable) {
            Log.w(TAG, "loadSuggestionApps: ${e.message}")
            emptyList()
        }
    }
}
