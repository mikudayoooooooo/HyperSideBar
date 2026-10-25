package com.lsp.hypersidebar.util

import android.content.ContentResolver
import android.provider.Settings
import com.lsp.hypersidebar.prefs.PrefKeys
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * 上下文启动记录（条件式推荐的自有信号，2026-10-24 计划 §A3）。
 *
 * 记录 `(source=呼出扇形时的前台应用, target=经扇形小窗打开的应用, ts)`，7 天滑窗，
 * 每对 (source,target) 保留最新 10 条（镜像 ROM 每包 10 条的封顶习惯）。
 * 存储 = `Settings.Secure[PrefKeys.SECURE_CONTEXT_LAUNCH_LOG]`：:ui 单写
 * （`WRITE_SECURE_SETTINGS`），launcher/:ui 多读——跨进程免新 IPC。
 *
 * 纯核心（parse/serialize/prune/append/counts）零状态可单测；Android 读写仅两个
 * 薄函数。**不写回 ROM 的 `freeform_timestamps`**（那是 ROM 自己的数据，§7 红线）。
 */
object ContextLaunchLog {

    /** 与 ROM 时间戳/launchCount 同窗（近一周，§0 三代实证） */
    const val WINDOW_MS = RomSignals.ROM_WINDOW_MS

    /** 每对 (source,target) 时间戳保留上限（超出丢最旧） */
    const val PER_PAIR_CAP = 10

    /** source → target → 升序时间戳 */
    fun parse(json: String?): Map<String, Map<String, List<Long>>> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Map<String, List<Long>>>()
        for (src in root.keys().asSequence()) {
            val tgtObj = runCatching { root.optJSONObject(src) }.getOrNull() ?: continue
            val targets = LinkedHashMap<String, List<Long>>()
            for (tgt in tgtObj.keys().asSequence()) {
                val arr = runCatching { tgtObj.optJSONArray(tgt) }.getOrNull() ?: continue
                val ts = (0 until arr.length()).mapNotNull { arr.optLong(it, -1L).takeIf { v -> v > 0 } }
                if (ts.isNotEmpty()) targets[tgt] = ts.sorted()
            }
            if (targets.isNotEmpty()) out[src] = targets
        }
        return out
    }

    /** 序列化按字典序（TreeMap）——同内容输出恒等，供「没变不写」比较 */
    fun serialize(log: Map<String, Map<String, List<Long>>>): String {
        val root = JSONObject()
        TreeMap(log).forEach { (src, targets) ->
            val t = JSONObject()
            TreeMap(targets).forEach { (tgt, ts) -> t.put(tgt, JSONArray(ts)) }
            root.put(src, t)
        }
        return root.toString()
    }

    /** 7 天剪枝（`now - ts >= 窗口` 即删，与 ROM 同语义）；空对/空源一并清 */
    fun prune(log: Map<String, Map<String, List<Long>>>, nowMs: Long): Map<String, Map<String, List<Long>>> {
        val out = LinkedHashMap<String, Map<String, List<Long>>>()
        log.forEach { (src, targets) ->
            val kept = LinkedHashMap<String, List<Long>>()
            targets.forEach { (tgt, ts) ->
                val live = ts.filter { nowMs - it < WINDOW_MS }
                if (live.isNotEmpty()) kept[tgt] = live
            }
            if (kept.isNotEmpty()) out[src] = kept
        }
        return out
    }

    /** 追加一条记录（保持升序 + 每对封顶丢最旧）；source/target 非法（空/自指）原样返回 */
    fun append(
        log: Map<String, Map<String, List<Long>>>,
        source: String,
        target: String,
        nowMs: Long
    ): Map<String, Map<String, List<Long>>> {
        if (source.isBlank() || target.isBlank() || source == target) return log
        val targets = LinkedHashMap(log[source].orEmpty())
        val ts = (targets[target].orEmpty() + nowMs).sorted()
        targets[target] = if (ts.size > PER_PAIR_CAP) ts.subList(ts.size - PER_PAIR_CAP, ts.size) else ts
        val out = LinkedHashMap(log)
        out[source] = targets
        return out
    }

    /** 排序用：`log[source]` 各 target 的（调用方已剪枝的）计数——喂 SuggestionRanker */
    fun counts(log: Map<String, Map<String, List<Long>>>, source: String?): Map<String, Int> {
        if (source == null) return emptyMap()
        return log[source].orEmpty().mapValues { it.value.size }
    }

    /** 全源计数表（DataLoader 原料仓缓存用；调用方已剪枝） */
    fun countsAll(log: Map<String, Map<String, List<Long>>>): Map<String, Map<String, Int>> =
        log.mapValues { (_, targets) -> targets.mapValues { it.value.size } }

    // ===== Android 读写（薄封装；对象无 init 期状态，JVM 可测纯核心） =====

    fun read(res: ContentResolver): Map<String, Map<String, List<Long>>> =
        runCatching {
            parse(Settings.Secure.getString(res, PrefKeys.SECURE_CONTEXT_LAUNCH_LOG))
        }.getOrDefault(emptyMap())

    /** :ui 专用（唯一写入方）：读→剪→记→写；返回是否落盘成功 */
    fun record(res: ContentResolver, source: String?, target: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (source.isNullOrBlank() || target.isBlank() || source == target) return false
        return runCatching {
            val log = append(prune(read(res), nowMs), source, target, nowMs)
            Settings.Secure.putString(res, PrefKeys.SECURE_CONTEXT_LAUNCH_LOG, serialize(log))
        }.getOrDefault(false)
    }
}
