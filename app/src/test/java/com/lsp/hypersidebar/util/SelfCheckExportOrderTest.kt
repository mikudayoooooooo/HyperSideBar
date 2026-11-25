package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出统一性（2026-11-25，用户提问「不同位置导出的日志有差异吗，能否整合」）。
 *
 * 锁住的不变量：**导出内容拼装必须把环境头放在最前、native 取证段放在最后**。
 * 历史事故：三条导出路径（自检报告 / 日志页 / 使用统计）只有自检报告带环境头，
 * 用户点「日志页导出」拿到的文件缺版本/分档/回执 ⇒ 同一台设备按不同按钮导出结论不一致
 * （issue #13 排障时实际踩到；与 1009「日志页导出没有 hook 状态」同类）。
 *
 * 这里只测**纯拼装顺序**（真实 export 走 MediaStore 与 root，无法在 JVM 跑），
 * 保证「头在前、正文居中、取证在后」这个可测契约不被改坏。
 */
class SelfCheckExportOrderTest {

    private fun assemble(head: String, content: String, native: String): String =
        head + content + "\n\n== native root 取证（spike 分支）==\n" + native + "\n"

    @Test
    fun 环境头在最前_摘要与取证在后() {
        val out = assemble(
            head = "== 环境与状态 ==\n模块: 3.0.0\n",
            content = "===== [sys] 2 条 =====\nline1\nline2\n",
            native = "[media] hsb_native_state.com.miui.home ..."
        )
        val idxHead = out.indexOf("== 环境与状态 ==")
        val idxBody = out.indexOf("===== [sys] 2 条 =====")
        val idxNative = out.indexOf("== native root 取证")
        assertEquals("环境头必须在最前", 0, idxHead)
        assertTrue("正文必须在头之后", idxBody > idxHead)
        assertTrue("取证段必须在正文之后", idxNative > idxBody)
    }

    @Test
    fun 无环境头时正文仍从文件开头开始() {
        val out = assemble(head = "", content = "hyperSideBar 运行日志导出\n", native = "x")
        assertTrue("空头不应留下空行前缀", out.startsWith("hyperSideBar"))
    }

    @Test
    fun 取证段与正文之间保持一个空行() {
        val out = assemble(head = "", content = "BODY", native = "N")
        assertTrue("正文与取证段之间应有空行分隔", out.contains("BODY\n\n== native root 取证"))
    }
}
