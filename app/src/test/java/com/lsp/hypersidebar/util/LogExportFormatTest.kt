package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志导出分段格式化（2026-11-24）。
 *
 * 锁住的不变量：**缓冲元信息必须出现在段头之后、首行之前**——它是判读
 * "导出里缺某行是没发生还是被环滚掉"的唯一依据（keepDropped/dropped/entries）。
 * 历史事故：日志页导出不带 meta，只有自检报告带 ⇒ 同一台设备按不同按钮导出，
 * 结论不一样（1009 同类问题，2026-11-23 issue #13 复现）。
 */
class LogExportFormatTest {

    @Test
    fun 段头后紧跟缓冲元信息再是日志行() {
        val out = LogExportFormat.build(
            header = "hyperSidebar 运行日志导出",
            sections = listOf(
                Triple("sys", """{"entries":198,"dropped":0,"keepDropped":0}""",
                    listOf("11-24 22:00:00.000 I [sys/TurboLayout] x"))
            )
        )
        val lines = out.lines()
        val headIdx = lines.indexOfFirst { it.startsWith("===== [sys] 1 条 =====") }
        assertTrue("段头必须存在", headIdx >= 0)
        assertTrue(
            "元信息必须紧贴段头下一行（got: ${lines.getOrNull(headIdx + 1)}）",
            lines[headIdx + 1].startsWith("(缓冲: ") && lines[headIdx + 1].contains("keepDropped")
        )
        assertEquals(
            "日志行必须在元信息之后",
            "11-24 22:00:00.000 I [sys/TurboLayout] x",
            lines[headIdx + 2]
        )
    }

    @Test
    fun 元信息缺失时不伪造空行() {
        val out = LogExportFormat.build(
            header = "",
            sections = listOf(Triple("cache", null, listOf("line-a")))
        )
        assertTrue("无 meta 时不应出现 (缓冲: 行", !out.contains("(缓冲:"))
        assertTrue("段头仍需存在", out.contains("===== [cache] 1 条 ====="))
    }

    @Test
    fun 多段按传入顺序且段间空行() {
        val out = LogExportFormat.build(
            header = "H",
            sections = listOf(
                Triple("app", """{"entries":2}""", listOf("a1", "a2")),
                Triple("sys", """{"entries":1}""", listOf("s1")),
            )
        )
        val idxApp = out.indexOf("===== [app] 2 条 =====")
        val idxSys = out.indexOf("===== [sys] 1 条 =====")
        assertTrue("app 段在 sys 段之前", idxApp in 0 until idxSys)
        assertTrue("段前有空行", out.contains("\n\n===== [sys]"))
    }

    @Test
    fun 头部无换行时自动补() {
        val out = LogExportFormat.build("HEADER", emptyList())
        assertEquals("HEADER", out.trimEnd())
        assertTrue("头部后应补换行", out.startsWith("HEADER\n"))
    }
}
