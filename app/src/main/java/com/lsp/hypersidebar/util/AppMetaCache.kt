package com.lsp.hypersidebar.util

import android.content.Context
import android.util.LruCache

/**
 * 应用标签缓存（pkg → label）：消灭扇形呼出时逐应用的主线程 PackageManager binder 调用。
 * 首次呼出填充，进程内复用；预热（Phase 6）会把首次填充也挪出关键路径。
 */
object AppMetaCache {

    private val labels = LruCache<String, String>(256)

    fun label(context: Context, pkg: String): String {
        labels.get(pkg)?.let { return it }
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrDefault(pkg)
        labels.put(pkg, label)
        return label
    }
}
