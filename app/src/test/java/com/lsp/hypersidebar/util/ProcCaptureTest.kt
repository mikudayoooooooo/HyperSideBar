package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [ProcCapture] 行为单测：真起子进程验证三条契约——
 * 1. 子进程写满管道且**不退出**时，超时仍然生效（修复前的核心缺陷：先 readText 后 waitFor ⇒ 永不返回）；
 * 2. 子进程正常退出时拿到真实 exitCode 与 stderr；
 * 3. 无输出不退出时同样按时返回 exitCode = -1 且内容为空。
 */
class ProcCaptureTest {

    private val javaHome = System.getProperty("java.home")

    /** Windows 是 `java.exe`，其余平台是 `java`（Gradle 测试跑在 JBR 上，两者都有）。 */
    private val javaBin = listOf("java.exe", "java")
        .map { File(javaHome ?: "", "bin/$it") }
        .firstOrNull { it.isFile }

    private val testClasses: String? = ProcCaptureFixture::class.java.protectionDomain?.codeSource
        ?.location?.let { File(it.toURI()).absolutePath }

    private fun start(mode: String): Process {
        val java = javaBin
        val classes = testClasses
        assumeTrue("找不到 java 可执行文件（java.home=$javaHome）", java != null)
        assumeTrue("fixture 不在 classes 目录：$classes", classes != null && File(classes).isDirectory)
        val classpath = buildList {
            add(classes!!)
            runCatching {
                kotlin.Unit::class.java.protectionDomain?.codeSource?.location?.let {
                    add(File(it.toURI()).absolutePath)
                }
            }
        }.joinToString(File.pathSeparator)
        return ProcessBuilder(
            java!!.absolutePath,
            "-cp",
            classpath,
            "com.lsp.hypersidebar.util.ProcCaptureFixture",
            mode
        ).start()
    }

    @Test
    fun fillStderrThenHang_stillTimesOut() {
        val process = start("fillErrThenHang")
        val t0 = System.currentTimeMillis()
        val result = ProcCapture.capture(process, timeoutMs = 1_500)
        val elapsed = System.currentTimeMillis() - t0

        assertEquals("超时必须返回 exitCode=-1", -1L, result.exitCode.toLong())
        assertTrue("耗时 ${elapsed}ms 应接近 1.5s 而非死等", elapsed < 3_000)
        assertEquals(
            "保留上限内应读满（说明超限后仍在读、未被管道堵死）",
            ProcCapture.MAX_STREAM_BYTES.toLong(),
            result.stderr.length.toLong()
        )
        assertEquals(0L, result.stdout.length.toLong())
        assertTrue("超时后应被强杀", process.waitFor(2, TimeUnit.SECONDS))
    }

    @Test
    fun normalExit_reportsCodeAndStderr() {
        val process = start("exit3")
        val result = ProcCapture.capture(process, timeoutMs = 10_000)

        assertEquals(3L, result.exitCode.toLong())
        assertTrue("stderr=${result.stderr}", result.stderr.contains("boom"))
        assertEquals(0L, result.stdout.length.toLong())
    }

    @Test
    fun silentHang_returnsEmptyOnTimeout() {
        val process = start("hang")
        val t0 = System.currentTimeMillis()
        val result = ProcCapture.capture(process, timeoutMs = 800)
        val elapsed = System.currentTimeMillis() - t0

        assertEquals(-1L, result.exitCode.toLong())
        assertTrue("耗时 ${elapsed}ms", elapsed >= 700 && elapsed < 3_000)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
    }
}
