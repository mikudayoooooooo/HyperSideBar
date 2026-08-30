package com.lsp.hypersidebar.util

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap

/**
 * 应用图标缓存（pkg → 128px Bitmap，1C 审计后的 P1 配套）：
 * 此前 AllApps 面板逐磁贴在主线程组合期做 PM binder + drawable 解码，且 LazyGrid
 * 滑出视口即弃、回滑重拉——滑动卡顿主因。进程级 LruCache 使回滑零成本，binder/解码
 * 全部挪到调用方指定的后台线程（本类不做线程管理）。
 * 上限 64 张：128×128×4 = 64KB/张 ≈ 4MB 常驻上限，覆盖准入列表高频区足够。
 */
object AppIconCache {

    private const val MAX_ENTRIES = 64
    private const val ICON_SIZE_PX = 128

    private val cache = object : LruCache<String, Bitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
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
}
