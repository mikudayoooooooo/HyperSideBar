package com.lsp.hypersidebar.util

/**
 * [ProcCaptureTest] 的子进程 fixture（test 源集，以 `java -cp <testClasses> … ProcCaptureFixture <mode>`
 * 启动；不能依赖 Android 运行时）。
 *
 * mode：
 * - `fillErrThenHang`：往 stderr 写 600KB（远超 64KB 管道缓冲，要求父进程真在读），然后不退出；
 * - `exit3`：写 `boom` 到 stderr 后以退出码 3 结束；
 * - `hang`：不写任何输出、不退出。
 */
object ProcCaptureFixture {

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "fillErrThenHang" -> {
                val chunk = ByteArray(1024) { 'e'.code.toByte() }
                repeat(600) {
                    System.err.write(chunk)
                    System.err.flush()
                }
                Thread.sleep(30_000)
            }
            "exit3" -> {
                System.err.write("boom".toByteArray())
                System.err.flush()
                System.exit(3)
            }
            "hang" -> Thread.sleep(30_000)
        }
    }
}
