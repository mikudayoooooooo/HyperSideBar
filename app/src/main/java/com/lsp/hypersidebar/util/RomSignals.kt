package com.lsp.hypersidebar.util

import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedList

/**
 * ROM 侧推荐信号解析（纯 JVM，2026-10-24 计划 §A2）。
 *
 * 三代反编译实证（§0 证据表）：
 * - `freeform_timestamps` 外层 JSONArray 的元素是 **String**（`MiuiMultiWindowUtils.java:2680`
 *   `(String) jArray.get(i)` 实锤）；jadx 的 write 侧输出疑失真 ⇒ 解析器**双形态兼容**
 *   （String 与 Object 都认）。
 * - ROM 7 天剪枝语义 `now - ts >= 604800000` 即删（**恰 7 天算过期**）。
 * - 每包上限 10 条在**写入时**强制（不依赖剪枝）——我们只读，无界增长不存在。
 *
 * **只读红线**：本对象绝不写回 `freeform_timestamps`——ROM `readFreeformTimestamps`
 * 解析失败会把整个键清空（`:2690-2696`），写坏 = 毁 ROM 自己的数据。
 */
object RomSignals {

    /** ROM 剪枝窗口（7d，与上下文记录同窗；`>=` 即删——三代一致） */
    const val ROM_WINDOW_MS = 7L * 24 * 3600 * 1000

    /**
     * 只读解析 ROM `Settings.Secure["freeform_timestamps"]` + 内存 7 天剪枝，
     * 构造反射 `calFreeformSuggestionList` 要求的精确形参类型
     * `HashMap<String, LinkedList<Long>>`（形参是 HashMap 非 Map，LinkedList 逐字照抄）。
     * null / 空 / 全坏 ⇒ 空 map（调用方决定降级）。
     */
    fun parseFreeformTimestamps(json: String?, nowMs: Long): HashMap<String, LinkedList<Long>> {
        val out = HashMap<String, LinkedList<Long>>()
        if (json.isNullOrBlank()) return out
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            // 双形态：ROM 实锤是 String 元素；Object 形态为 jadx 失真防御，两者都认
            val obj = arr.optJSONObject(i)
                ?: runCatching { JSONObject(arr.optString(i)) }.getOrNull()
                ?: continue
            val pkg = obj.keys().asSequence().firstOrNull() ?: continue
            val list = runCatching { obj.optJSONArray(pkg) }.getOrNull() ?: continue
            val ts = LinkedList<Long>()
            for (j in 0 until list.length()) {
                val t = list.optLong(j, -1L)
                // 剪枝：ROM 语义 now - t >= 7d 即删（恰 7 天算过期；非法 -1 一并丢弃）
                if (t > 0 && nowMs - t < ROM_WINDOW_MS) ts.addLast(t)
            }
            if (ts.isNotEmpty()) out[pkg] = ts
        }
        return out
    }

    // ===== 自有落盘（DataLoader v2 分数表；旧数组键只为兼容读） =====

    /** v2 分数表（JSON 对象 pkg→score）；null/坏 ⇒ null（调用方走 legacy 兼容） */
    fun parsePersistedScores(json: String?): Map<String, Int>? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val out = LinkedHashMap<String, Int>()
        for (key in obj.keys().asSequence()) {
            val v = obj.optInt(key, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) out[key] = v
        }
        return out
    }

    fun serializePersistedScores(scores: Map<String, Int>): String {
        val obj = JSONObject()
        scores.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    /** 旧数组格式（今日的 suggestions 键）：只为升级兼容读 */
    fun parsePersistedLegacy(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it) }.filter { it.isNotEmpty() }
    }
}
