package com.lsp.hypersidebar.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 子进程捕获结果（[exitCode] -1 = 超时被强杀）。 */
class ProcResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * 子进程输出捕获（纯 `java.lang`/`java.io`，无 Android 依赖 ⇒ 可 JVM 单测）。
 *
 * 修 2026-11-02 审查 P1：原 `runSuCapture` 先 `readText()` 两条流、后 `waitFor(timeout)`——
 * 子进程不退出或不关流时超时**永不生效**（`su` 授权弹窗未响应 = 死等），且顺序读两条流
 * 仍是 64KB 管道互等（子进程写满 stderr 就阻塞，父进程正卡在 stdout 的 `readText`）。
 *
 * 现行契约：
 * - 两条流各由一条 daemon 线程**并行** drain，超过 [MAX_STREAM_BYTES] 的部分继续读并丢弃
 *   （停读会让子进程写管道阻塞，反而制造死锁）；
 * - 主线程只做 `waitFor(timeoutMs)`，超时即 `destroyForcibly()` ⇒ 返回 exitCode = -1；
 * - 两条 drain 线程的 join 各有上限（子进程退出后流通常立即 EOF；孙进程持管道时不死等）。
 */
object ProcCapture {
    const val DEFAULT_TIMEOUT_MS = 10_000L

    /** 单流保留上限（超出部分继续读并丢弃，绝不停止读取——停读会让子进程写管道阻塞） */
    const val MAX_STREAM_BYTES = 256 * 1024

    /** 子进程退出后等 drain 线程收尾的上限（孙进程继承管道时会读不到 EOF） */
    private const val DRAIN_JOIN_MS = 500L

    fun capture(
        process: Process,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxStreamBytes: Int = MAX_STREAM_BYTES
    ): ProcResult {
        val out = Drainer(process.inputStream, maxStreamBytes)
        val err = Drainer(process.errorStream, maxStreamBytes)
        var timedOut: Boolean
        try {
            timedOut = !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            timedOut = true
        }
        if (timedOut) runCatching { process.destroyForcibly() }
        out.await(DRAIN_JOIN_MS)
        err.await(DRAIN_JOIN_MS)
        val exit = if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-1)
        if (process.isAlive) runCatching { process.destroyForcibly() }
        return ProcResult(exit, out.text(), err.text())
    }

    /** 单流 drain：后台 daemon 线程读满上限即丢弃，`text()` 取得已保留部分。 */
    private class Drainer(stream: InputStream, private val maxBytes: Int) {
        private val buffer = ByteArrayOutputStream()
        private val lock = Any()
        private val thread = Thread({ drain(stream) }, "ProcCapture-drain").apply {
            isDaemon = true
            start()
        }

        private fun drain(stream: InputStream) {
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(lock) {
                        val room = maxBytes - buffer.size()
                        if (room > 0) buffer.write(chunk, 0, minOf(room, n))
                    }
                }
            } catch (e: Throwable) {
                // 进程被杀/流被关：正常退出路径，保留已读内容
                if (e is InterruptedException) Thread.currentThread().interrupt()
            } finally {
                runCatching { stream.close() }
            }
        }

        fun await(ms: Long) {
            try {
                thread.join(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        fun text(): String = synchronized(lock) { String(buffer.toByteArray(), Charsets.UTF_8) }
    }
}
