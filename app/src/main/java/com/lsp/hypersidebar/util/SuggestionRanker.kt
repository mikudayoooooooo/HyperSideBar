package com.lsp.hypersidebar.util

/**
 * 条件式推荐排序核心（纯 JVM，2026-10-24 计划 §A1 定案）。
 *
 * 口径（§0 三代反编译实证）：ROM 侧 score = 近一周总启动次数 + 10 × 小窗时间戳数
 * （含原生侧边栏行为）；本排序在其上叠 `weight × min(当前来源→目标的计数, cap)`——
 * **有意叠加而非替代**：ROM 那份时间戳包含原生侧边栏/最近任务的小窗启动，我们只认
 * 自己的上下文记录。自有记录不可用时调用方传 source=null（或 weight=0），自然退回
 * ROM 原序，**不得**退化成"数 ROM 时间戳 ×N"的口径。
 *
 * 排序键 `final desc → romScore desc → packageName asc`：末位消除 Map 迭代序导致的
 * 同分抖动，保证同一输入输出确定（A1 验收：×1000 一致）。
 */
object SuggestionRanker {

    /**
     * @param romScores      ROM 准入列表的分数（calFreeformSuggestionList 产物，可为空=未就绪）
     * @param contextCounts  source → target → 7 天内启动次数（ContextLaunchLog 内存剪枝后）
     * @param currentSource  本次呼出冻结的前台应用；null/未知/launcher/模块自身由调用方
     *                       归一为 null ⇒ boost=0
     * @param weight         每次上下文启动的加权（LayoutDefaults.REC_CTX_WEIGHT）
     * @param cap            单目标计数封顶（LayoutDefaults.REC_CTX_CAP）
     * @return 包名有序列表：候选 = romScores ∪ contextCounts[currentSource]
     *         （仅上下文侧的包 romScore 记 0 保留；仅 ROM 侧的零分包保留）
     */
    fun rank(
        romScores: Map<String, Int>,
        contextCounts: Map<String, out Map<String, Int>>,
        currentSource: String?,
        weight: Int,
        cap: Int
    ): List<String> {
        if (romScores.isEmpty() && currentSource == null) return emptyList()
        val boost = if (currentSource == null) emptyMap() else contextCounts[currentSource].orEmpty()
        val candidates = romScores.keys + boost.keys
        if (candidates.isEmpty()) return emptyList()

        fun finalScore(pkg: String): Int {
            val rom = romScores[pkg] ?: 0
            val cnt = (boost[pkg] ?: 0).coerceIn(0, cap.coerceAtLeast(0))
            return rom + weight * cnt
        }

        return candidates.sortedWith(
            compareByDescending<String> { finalScore(it) }
                .thenByDescending { romScores[it] ?: 0 }
                .thenBy { it }
        )
    }
}
