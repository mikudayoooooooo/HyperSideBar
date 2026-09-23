package com.lsp.hypersidebar.anchor

import com.lsp.hypersidebar.util.HLog
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitCacheBridge
import java.io.File

/**
 * DexKit 查询结果缓存的落盘后端（宿主 cacheDir 下的一个 JSON 文件）。
 *
 * 为什么由我们自己实现：DexKit 的 `DexKitCacheBridge` 只定义 `Cache` SPI（7 个方法，实测 2.3.0 源码
 * `DexKitCacheBridge.kt:197-205`），存取介质交给调用方 —— 它不知道宿主进程能写哪里。
 * hook 代码跑在**宿主的 uid** 里，写不了模块自己的数据目录，所以落点只能是宿主 `cacheDir`；
 * 文件名统一 `hypersidebar_` 前缀，进程异常退出时由系统清缓存。
 *
 * **[tag] 是失效闸**：文件里记着写入时的 appTag（`宿主包#versionCode#apkLength`）。
 * ROM 一升级 tag 就变 ⇒ 旧条目整体作废（旧版本解析出来的类名对新 ROM 没有意义），
 * 这正是"检测到版本更新后再次解析"的落地点。
 *
 * 铁律：**缓存只决定"要不要重新查询"，永不决定 hook 目标** —— 命中缓存的类名仍要过 L1 反射校验。
 * 因此这里的实现**永不抛异常**（读写失败都当作 miss），缓存坏了不能影响解析正确性。
 */
internal class AnchorDiskCache(private val file: File, private val tag: String) : DexKitCacheBridge.Cache {

    private val lock = Any()
    private var loaded = false
    private val strings = HashMap<String, String>()
    private val lists = HashMap<String, List<String>>()

    /**
     * 统一加锁 + 吞异常。每个方法各自给出失败时的无害回退值
     * （读⇒null、写⇒Unit、列举⇒空集合），避免用泛型强转兜底。
     */
    private inline fun <R> guarded(fallback: R, block: () -> R): R = synchronized(lock) {
        try {
            ensureLoaded()
            block()
        } catch (t: Throwable) {
            HLog.w(TAG, "cache op failed (treated as miss): ${t.message}")
            fallback
        }
    }

    override fun getString(key: String, default: String?): String? =
        guarded(null) { strings[key] ?: default }

    override fun putString(key: String, value: String): Unit = guarded(Unit) {
        strings[key] = value
        trimIfNeeded()
        persist()
    }

    override fun getStringList(key: String, default: List<String>?): List<String>? =
        guarded(null) { lists[key] ?: default }

    override fun putStringList(key: String, value: List<String>): Unit = guarded(Unit) {
        lists[key] = value
        trimIfNeeded()
        persist()
    }

    override fun remove(key: String): Unit = guarded(Unit) {
        strings.remove(key)
        lists.remove(key)
        persist()
    }

    override fun getAllKeys(): Collection<String> =
        guarded(emptySet<String>()) { (strings.keys + lists.keys).toSet() }

    override fun clearAll(): Unit = guarded(Unit) {
        strings.clear()
        lists.clear()
        runCatching { file.delete() }
        Unit
    }

    // ===== 内部 =====

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            val stored = root.optString("tag", "")
            if (stored != tag) {
                // 宿主版本变了：旧解析结果一律作废
                HLog.i(TAG, "cache tag changed ($stored -> $tag), dropping ${strings.size + lists.size} entries")
                strings.clear()
                lists.clear()
                return@runCatching
            }
            root.optJSONObject("strings")?.let { obj ->
                obj.keys().forEach { k -> strings[k] = obj.optString(k) }
            }
            root.optJSONObject("lists")?.let { obj ->
                obj.keys().forEach { k ->
                    val arr = obj.optJSONArray(k) ?: return@forEach
                    lists[k] = (0 until arr.length()).map { arr.optString(it) }
                }
            }
            HLog.i(TAG, "cache loaded: ${strings.size} strings / ${lists.size} lists (tag=$tag)")
        }.onFailure { HLog.w(TAG, "cache file unreadable, starting empty: ${it.message}") }
    }

    private fun trimIfNeeded() {
        if (strings.size + lists.size <= MAX_ENTRIES) return
        HLog.w(TAG, "cache over $MAX_ENTRIES entries, dropping all")
        strings.clear()
        lists.clear()
    }

    private fun persist() {
        val root = JSONObject()
        root.put("tag", tag)
        root.put("strings", JSONObject().apply { strings.forEach { (k, v) -> put(k, v) } })
        root.put(
            "lists",
            JSONObject().apply {
                lists.forEach { (k, v) ->
                    put(k, JSONArray().apply { v.forEach { put(it) } })
                }
            }
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            file.parentFile?.mkdirs()
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error("rename failed")
            }
        }.onFailure {
            HLog.w(TAG, "cache persist failed: ${it.message}")
            runCatching { tmp.delete() }
        }
    }

    private companion object {
        const val TAG = "AnchorCache"

        /** 条目上限；超过就整体丢弃重来（缓存是纯优化，不值得为它做 LRU） */
        const val MAX_ENTRIES = 512
    }
}