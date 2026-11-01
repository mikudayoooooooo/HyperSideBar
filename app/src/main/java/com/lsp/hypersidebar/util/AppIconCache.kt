package com.lsp.hypersidebar.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.lsp.hypersidebar.util.HLog

/**
 * 应用图标缓存（pkg → 128px Bitmap，迭代二 P1）：
 * 此前 AllApps 面板逐磁贴在主线程组合期做 PM binder + drawable 解码，且 LazyGrid
 * 滑出视口即弃、回滑重拉——滑动卡顿主因。进程级 LruCache 使回滑零成本，binder/解码
 * 全部挪到后台线程。
 *
 * 上限 320 张（全量准入列表 219 + 余量，≈14-20MB、仅面板进程内）：配合 [preload]
 * 面板打开后 4 线程并行预灌缓存，快滑时新磁贴基本同帧命中，消除快滑时的解码风暴与重组洪水。
 */
private const val TAG = "AppIconCache"

object AppIconCache {

    private const val MAX_ENTRIES = 320
    private const val ICON_SIZE_PX = 128

    private val cache = LruCache<String, Bitmap>(MAX_ENTRIES)

    // 预加载池：4 线程（2026-09-05 真机实测：冷进程 223 图标单线程串行 1330ms，是磁贴
    // 占位态时长的主要构成）。逐磁贴 miss 路径（AppTile→load on Dispatchers.IO）不经此池，
    // 原"单线程限并发防 IO 风暴"约束只针对 preload 自身，提并行无副作用
    private val loader = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "AppIconLoader").apply { isDaemon = true }
    }

    /** 仅查缓存，不触发 binder（主线程组合路径用）。 */

    /** 热重载释放（2026-10-18）：旧代的线程池跨代存活（守护线程不会自己退），
     *  钉死旧 classloader；新代是全新 object 自带新池。未起过 = 空操作。 */
    fun releaseForReload() {
        runCatching { loader.shutdownNow() }
            .onFailure { HLog.w(TAG, "executor shutdown failed: ${it.message}") }
        runCatching { cache.evictAll() }
            .onFailure { HLog.w(TAG, "cache evict failed: ${it.message}") }
        runCatching { inflight.clear() }
            .onFailure { HLog.w(TAG, "inflight clear failed: ${it.message}") }
        runCatching { HLog.i(TAG, "releaseForReload: done") }
    }
    fun peek(pkg: String): Bitmap? = cache.get(pkg)

    /**
     * 进行中的解码任务（single-flight，2026-10-30）：预灌（4 线程池）与逐磁贴 miss
     * （每磁贴一个 IO 协程）此前对同一包各做一遍 binder + `toBitmap(128)`；
     * 面板冷开时固定区与「全部」区同包并存，等于白做一轮解码。
     * 同一包同一时刻只跑一个任务，其余调用者等它的结果。
     */
    private val inflight =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<Bitmap?>>()

    /** 查缓存 + miss 时 binder 加载解码（同包并发自动合流）。必须在后台线程调用。 */
    fun load(context: Context, pkg: String): Bitmap? {
        cache.get(pkg)?.let { return it }
        val task = inflight.computeIfAbsent(pkg) { key ->
            java.util.concurrent.FutureTask { decode(context, key) }
        }
        // FutureTask.run 幂等：只有第一个调用者真正执行，其余立即返回后等 get()
        runCatching { task.run() }
        val result = runCatching { task.get() }.getOrNull()
        inflight.remove(pkg, task)
        return result ?: cache.get(pkg)
    }

    /** 真正干活的那一段（不进 inflight 表，避免自等）。 */
    private fun decode(context: Context, pkg: String): Bitmap? {
        cache.get(pkg)?.let { return it }
        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
        }.onFailure {
            HLog.w(TAG, "icon load failed: $pkg (${it.javaClass.simpleName}: ${it.message})")
        }.getOrNull()
        if (bitmap != null) cache.put(pkg, bitmap)
        return bitmap
    }

    /** 面板打开后全量预灌缓存（后台 4 线程并行，立即返回）。
     *  耗时锚点：冷启动时本段是磁贴占位态时长的主要构成（逐个 binder+解码）。 */
    fun preload(context: Context, pkgs: List<String>) {
        if (pkgs.isEmpty()) return
        val t0 = android.os.SystemClock.elapsedRealtime()
        val targets = pkgs.distinct()
        val remaining = java.util.concurrent.atomic.AtomicInteger(targets.size)
        targets.forEach { pkg ->
            loader.execute {
                runCatching { load(context, pkg) }
                if (remaining.decrementAndGet() == 0) {
                    HLog.i(TAG, "preload: ${targets.size} icons in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
                }
            }
        }
    }
}
