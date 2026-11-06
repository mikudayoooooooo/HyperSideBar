package com.lsp.hypersidebar.util

import android.content.Context
import com.lsp.hypersidebar.prefs.PrefsFiles
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ROM 侧“小窗资格”名单（2026-10-17 离线判读 + 反编译实证）。
 *
 * 来源：`android.util.MiuiMultiWindowUtils.getFreeformSuggestionList(Context) → List<String>`。
 * 该调用实测约 0.7–1s，**绝不能出现在启动路径/主线程上**。
 *
 * 现行策略（2026-11-05，用户拍板）：
 * - 启动路径只调 [peek]：纯内存读，未知返回 null（调用方按“尝试”处理）；
 * - [warm] 在 hook init 空闲期 hydrate 盘缓存 + 后台刷新；
 * - 每次打开 AllApps 调 [refreshAsync]：本次用旧值，后台更新供**下一次**打开使用；
 * - 不设 TTL；刷新失败不覆盖已有缓存。
 */
object FreeformEligibility {

    private const val TAG = "FreeformEligibility"
    private const val UTILS_CLASS = "android.util.MiuiMultiWindowUtils"
    private const val DISK_KEY = "freeform_eligibility"

    /** null = 未知（未 hydrate / ROM 取不到）。 */
    @Volatile private var cached: Set<String>? = null
    @Volatile private var hydrated = false
    private val refreshing = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FreeformEligibility").apply { isDaemon = true }
    }

    /** 启动路径专用：只读内存，无锁/无反射/无盘 I/O。 */
    fun peek(pkg: String): Boolean? = cached?.contains(pkg)

    /** hook init 空闲期调用（可在后台线程同步执行）：hydrate 盘缓存 + 触发后台刷新。 */
    fun warm(context: Context) {
        val appCtx = context.applicationContext ?: context
        hydrate(appCtx)
        refreshAsync(appCtx)
    }

    /**
     * 下一次打开 AllApps 时调用：立即返回，后台反射 ROM 名单并落盘。
     * 本次启动继续使用旧缓存；刷新失败保持旧值（没有旧值则保持“未知”）。
     */
    fun refreshAsync(context: Context) {
        if (!refreshing.compareAndSet(false, true)) return
        val appCtx = context.applicationContext ?: context
        runCatching {
            executor.execute {
                try {
                    hydrate(appCtx)
                    refreshNow(appCtx)
                } finally {
                    refreshing.set(false)
                }
            }
        }.onFailure {
            refreshing.set(false)
            HLog.w(TAG, "refresh enqueue failed: ${it.message}")
        }
    }

    /** API 102 热重载：旧代 executor 与缓存不得跨代。 */
    fun releaseForReload() {
        runCatching { executor.shutdownNow() }
            .onFailure { HLog.w(TAG, "executor shutdown failed: ${it.message}") }
        cached = null
        hydrated = false
        refreshing.set(false)
        HLog.i(TAG, "releaseForReload: done")
    }

    @Synchronized
    private fun hydrate(context: Context) {
        if (hydrated) return
        hydrated = true
        val raw = runCatching {
            context.getSharedPreferences(PrefsFiles.DATA, Context.MODE_PRIVATE)
                .getString(DISK_KEY, null)
        }.getOrNull()
        val entry = FreeformEligibilityCodec.decode(raw) ?: return
        cached = entry.packages
        HLog.i(TAG, "hydrated ${entry.packages.size} packages (ts=${entry.fetchedAt})")
    }

    /** 锁外反射：后台刷新期间启动路径的 [peek] 始终是 volatile 读，不会被锁住。 */
    private fun refreshNow(context: Context) {
        val result = runCatching {
            val cls = Class.forName(UTILS_CLASS)
            val m = cls.getMethod("getFreeformSuggestionList", Context::class.java)
            val list = m.invoke(null, context) as? List<*>
            list?.mapNotNull { it as? String }?.filter { it.isNotBlank() }?.toSet()
        }
        val packages = result.getOrNull()
        if (packages == null) {
            // 失败不覆盖已有缓存：没有缓存时保持“未知”，下次打开再试。
            HLog.w(TAG, "refresh failed: ${result.exceptionOrNull()?.message}")
            return
        }
        cached = packages
        runCatching {
            context.getSharedPreferences(PrefsFiles.DATA, Context.MODE_PRIVATE)
                .edit()
                .putString(
                    DISK_KEY,
                    FreeformEligibilityCodec.encode(
                        EligibilityEntry(System.currentTimeMillis(), packages)
                    )
                )
                .apply()
        }.onFailure { HLog.w(TAG, "persist failed: ${it.message}") }
        HLog.i(TAG, "refresh ok: ${packages.size} packages")
    }
}
