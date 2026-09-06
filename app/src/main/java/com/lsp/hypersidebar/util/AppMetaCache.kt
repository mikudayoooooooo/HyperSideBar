package com.lsp.hypersidebar.util

import android.content.Context
import android.util.Log
import android.util.LruCache

/**
 * 应用标签缓存（pkg → label）：消灭扇形呼出时逐应用的主线程 PackageManager binder 调用。
 * 首次呼出填充，进程内复用；预热（Phase 6）会把首次填充也挪出关键路径。
 *
 * 落盘镜像（2026-09-05 AllApps 实测：冷进程组装段 769ms=222 次冷 PM binder，label 变化
 * 极慢——全量快照写模块本地 prefs，冷启动 [warmFromDisk] 盘灌顶住，热路径 LruCache
 * 命中不受影响；[persistToDisk] 没变化不写，实际写盘趋近于零）。镜像文件与 AllApps
 * fallbackPrefs（hyperSidebar_prefs）同库，keys 见 PrefKeys.CACHED_LABELS。
 */
object AppMetaCache {

    private const val TAG = "AppMetaCache"
    private const val MIRROR_PREFS = "hyperSidebar_prefs"

    private val labels = LruCache<String, String>(256)

    @Volatile private var diskLabels: Map<String, String>? = null

    fun label(context: Context, pkg: String): String {
        labels.get(pkg)?.let { return it }
        diskLabels?.get(pkg)?.let {
            labels.put(pkg, it)
            return it
        }
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrDefault(pkg)
        labels.put(pkg, label)
        return label
    }

    /** 盘灌（IO 线程、每进程一次）：冷启动组装前调用，miss 先落盘值兜底再回 PM。 */
    fun warmFromDisk(context: Context) {
        if (diskLabels != null) return
        runCatching {
            val json = context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .getString(com.lsp.hypersidebar.prefs.PrefKeys.CACHED_LABELS, null) ?: return
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, String>()
            for (key in obj.keys()) map[key] = obj.optString(key)
            diskLabels = map
            Log.i(TAG, "warmFromDisk: ${map.size} labels")
        }.onFailure { Log.w(TAG, "warmFromDisk failed: ${it.message}") }
    }

    /** 全量快照写回（IO 线程）：没变化不写。 */
    fun persistToDisk(context: Context, snapshot: Map<String, String>) {
        if (snapshot.isEmpty()) return
        runCatching {
            val prefs = context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
            val json = org.json.JSONObject(snapshot).toString()
            if (prefs.getString(com.lsp.hypersidebar.prefs.PrefKeys.CACHED_LABELS, null) != json) {
                prefs.edit()
                    .putString(com.lsp.hypersidebar.prefs.PrefKeys.CACHED_LABELS, json)
                    .apply()
                Log.i(TAG, "persistToDisk: ${snapshot.size} labels")
            }
        }.onFailure { Log.w(TAG, "persistToDisk failed: ${it.message}") }
    }
}
