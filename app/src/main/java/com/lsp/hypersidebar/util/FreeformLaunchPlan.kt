package com.lsp.hypersidebar.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 小窗启动候选顺序（纯逻辑，可 JVM 单测）。
 *
 * 宿主包**无条件先试**（常见路径一次命中，且不依赖 ROM 资格名单）；
 * 模块包只在缓存未明确排除（`null`/`true`）时作为 fallback。
 */
internal object FreeformLaunchPlanner {
    fun candidates(host: String, module: String, moduleEligible: Boolean?): List<String> = buildList {
        if (host.isNotBlank()) add(host)
        if (module.isNotBlank() && module != host && moduleEligible != false) add(module)
    }
}

/** 资格名单盘缓存条目（`fetchedAt` 仅诊断，不做 TTL）。 */
internal data class EligibilityEntry(
    val fetchedAt: Long,
    val packages: Set<String>
)

/**
 * 资格名单盘缓存格式（format=1）：
 * `{"format":1,"ts":<ms>,"pkgs":["pkg", ...]}`
 *
 * 未知/损坏/版本不符一律返回 null —— 调用方保持“未知=尝试”的既有语义。
 */
internal object FreeformEligibilityCodec {
    private const val FORMAT = 1

    fun encode(entry: EligibilityEntry): String = JSONObject().apply {
        put("format", FORMAT)
        put("ts", entry.fetchedAt)
        put("pkgs", JSONArray(entry.packages.toList()))
    }.toString()

    fun decode(raw: String?): EligibilityEntry? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (obj.optInt("format", -1) != FORMAT) return null
        val arr = obj.optJSONArray("pkgs") ?: return null
        val packages = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val pkg = arr.optString(i, "")
            if (pkg.isNotBlank()) packages.add(pkg)
        }
        return EligibilityEntry(obj.optLong("ts", 0L), packages)
    }
}
