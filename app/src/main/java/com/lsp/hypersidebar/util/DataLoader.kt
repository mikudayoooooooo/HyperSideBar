package com.lsp.hypersidebar.util

import android.content.Context
import android.util.Log

/**
 * 扇形菜单数据源（2026-08-25 决策：provider 整条路径退役）。
 *
 * 可打开应用统一取自系统准入列表 `MiuiMultiWindowUtils.getFreeformSuggestionList`
 * （即 PRD §3.2 准入规则的产物——无小窗资格的应用天然不在其中，无需二次过滤）。
 * 30s TTL 缓存兜底反射调用开销；快捷栏数据（用户 shortcut_actions）不经此类（Phase 3 接入）。
 */
object DataLoader {

    private const val TAG = "DataLoader"
    private const val CACHE_TTL_MS = 30_000L

    private var cachedResult: List<String>? = null
    private var lastFetchTime = 0L

    fun loadApps(context: Context): List<String> {
        return try {
            loadAppsInternal(context)
        } catch (e: Throwable) {
            cachedResult ?: emptyList()
        }
    }

    private fun loadAppsInternal(context: Context): List<String> {
        val now = System.currentTimeMillis()
        if (cachedResult != null && (now - lastFetchTime) < CACHE_TTL_MS) {
            return cachedResult!!
        }

        val suggestion = loadSuggestionApps(context)
        cachedResult = suggestion
        lastFetchTime = now
        return suggestion
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
