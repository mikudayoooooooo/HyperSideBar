package com.lsp.hypersidebar.util

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap

/**
 * 应用图标缓存（pkg → 128px Bitmap，迭代二 P1）：
 * 此前 AllApps 面板逐磁贴在主线程组合期做 PM binder + drawable 解码，且 LazyGrid
 * 滑出视口即弃、回滑重拉——滑动卡顿主因。进程级 LruCache 使回滑零成本，binder/解码
 * 全部挪到后台线程。
 *
 * 上限 320 张（全量准入列表 219 + 余量，≈14-20MB、仅面板进程内）：配合 [preload]
 * 面板打开后顺序预灌缓存，快滑时新磁贴基本同帧命中，消除快滑时的解码风暴与重组洪水。
 */
object AppIconCache {

    private const val MAX_ENTRIES = 320
    private const val ICON_SIZE_PX = 128

    private val cache = LruCache<String, Bitmap>(MAX_ENTRIES)

    // 预加载专用单线程：顺序灌缓存（已缓存自动跳过）；单线程天然限制 binder/解码
    // 并发，避免快滑时逐磁贴 miss 的 IO 风暴
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppIconLoader").apply { isDaemon = true }
    }

    /** 仅查缓存，不触发 binder（主线程组合路径用）。 */
    fun peek(pkg: String): Bitmap? = cache.get(pkg)

    /** 查缓存 + miss 时 binder 加载解码。必须在后台线程调用。 */
    fun load(context: Context, pkg: String): Bitmap? {
        cache.get(pkg)?.let { return it }
        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
        }.getOrNull()
        if (bitmap != null) cache.put(pkg, bitmap)
        return bitmap
    }

    /** 面板打开后全量预灌缓存（后台单线程顺序执行，立即返回）。 */
    fun preload(context: Context, pkgs: List<String>) {
        if (pkgs.isEmpty()) return
        loader.execute {
            pkgs.distinct().forEach { pkg ->
                runCatching { load(context, pkg) }
            }
        }
    }
}
