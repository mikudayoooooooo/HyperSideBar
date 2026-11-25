package com.lsp.hypersidebar.util

/**
 * 日志导出的分段格式化核心（2026-11-24，issue #13 取证链收口）。
 *
 * 为什么抽成纯函数：日志页（`LogPage`）与自检报告（`SelfCheck`）是两条导出路径，
 * 历史教训是"按哪个按钮结果不一样"（1009：日志页导出里没有 hook 状态）。本函数把
 * 「段头 + 缓冲元信息 + 行体」这段唯一会漂移的部分单源化，并用纯 JVM 测试锁住
 * **meta 必须在段头之后、首行之前出现**——keepDropped/dropped 看不到时，
 * "导出里缺某行"就无法判读是"没发生"还是"被环滚掉"。
 *
 * 行体不在此处 formatLine（Entry 依赖系统时区格式化器）：调用方传入已格式化文本，
 * 本函数只负责分段骨架 ⇒ 纯 JVM 可测、无 Android 依赖。
 */
object LogExportFormat {

    /**
     * @param header 文件头（调用方拼的时间等；可为空串，结尾换行由本函数补）
     * @param sections 进程段：proc 短名 → (缓冲元信息 JSON，可空, 已格式化的行)
     */
    fun build(
        header: String,
        sections: List<Triple<String, String?, List<String>>>,
    ): String {
        val sb = StringBuilder()
        if (header.isNotEmpty()) {
            sb.append(header)
            if (!header.endsWith('\n')) sb.append('\n')
        }
        sections.forEach { (proc, meta, lines) ->
            sb.append("\n===== [")
                .append(proc)
                .append("] ")
                .append(lines.size)
                .append(" 条 =====\n")
            // 元信息紧贴段头：缺失时可判读为"该进程没回传 meta"，而不是"被截断"
            if (meta != null && meta.isNotEmpty()) {
                sb.append("(缓冲: ").append(meta).append(")\n")
            }
            lines.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }
}
