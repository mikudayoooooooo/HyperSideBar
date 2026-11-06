package com.lsp.hypersidebar.ui.allapps

import android.content.Context
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.PrefsFiles
import org.json.JSONObject

/** 分区首字母缓存编解码（纯 JVM 可测）。 */
internal object SectionLetterCodec {
    fun encode(letters: Map<String, String>): String = JSONObject(letters).toString()

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>(obj.length())
        for (key in obj.keys()) {
            val letter = obj.optString(key, "")
            if (letter.isNotBlank()) out[key] = letter
        }
        return out
    }
}

/**
 * label → 分区首字母的进程内 + 盘缓存（2026-11-05）。
 *
 * 冷进程首次组装 AllApps 时，262 个标签逐个走 ICU `Transliterator`（含 ICU 初始化）实测
 * ~1.2s；而首字母只取决于 label，跨进程/跨会话稳定。这里把结果落到模块本地 prefs，
 * 后续即使杀掉模块再打开也不再跑 ICU，只对新增 label 计算。
 */
internal object SectionLetterCache {

    private const val PREFS = PrefsFiles.APP_LOCAL
    private const val KEY = PrefKeys.CACHED_SECTION_LETTERS

    @Volatile private var letters: Map<String, String> = emptyMap()
    @Volatile private var warmed = false

    /** IO 线程调用：读盘一次（同一进程多次 no-op）。 */
    fun warm(context: Context) {
        if (warmed) return
        synchronized(this) {
            if (warmed) return
            val raw = runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, null)
            }.getOrNull()
            letters = SectionLetterCodec.decode(raw)
            warmed = true
        }
    }

    fun snapshot(): Map<String, String> = letters

    /** IO 线程调用：合并新算出的首字母并落盘；无变化不写。 */
    fun persist(context: Context, additions: Map<String, String>) {
        if (additions.isEmpty()) return
        synchronized(this) {
            val merged = HashMap(letters)
            var changed = false
            for ((label, letter) in additions) {
                if (merged[label] != letter) {
                    merged[label] = letter
                    changed = true
                }
            }
            if (!changed) return
            letters = merged
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY, SectionLetterCodec.encode(merged))
                    // commit：这份缓存的全部价值就是跨进程死亡保留，apply 的异步写可能来不及落盘
                    .commit()
            }
        }
    }
}
