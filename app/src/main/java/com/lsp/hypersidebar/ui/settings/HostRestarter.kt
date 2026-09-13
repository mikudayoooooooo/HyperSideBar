package com.lsp.hypersidebar.ui.settings

import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.util.HLog
import java.util.concurrent.TimeUnit

/**
 * 调试区「重启 hook 宿主」（0912）：模块更新后"三宿主全重启"运维规程的一键化
 * （漏 SystemUI=磁贴成功率回退，2026-09-06 实锤）。目标仅限本模块 hook 的三个宿主，
 * 包名全部取自 [HostPackages] 常量（无外部输入，无注入面）。
 *
 * 执行语义：模块进程 su -c `am force-stop` 逐宿主强杀；拉回交给系统各自规则——
 * - home：force-stop 后处于 stopped 态，追加 HOME intent 显式拉回；
 * - securitycenter：按需自启（呼出侧边栏时系统拉 :ui，hook 随进程重注入）；
 * - systemui：persistent，被杀即自动重启。
 */
internal object HostRestarter {

    /** 单条命令预算（su 冷授权可能停在授权弹窗，超时放弃该宿主并计入失败） */
    private const val COMMAND_TIMEOUT_MS = 8_000L

    data class Host(
        val pkg: String,
        val label: String,
        val summary: String,
        val relaunchHome: Boolean
    )

    val HOSTS = listOf(
        Host(HostPackages.HOME, "系统桌面", "边缘手势触发端 · ${HostPackages.HOME}", relaunchHome = true),
        Host(HostPackages.UI_HOST, "手机管家", ":ui 执行端（小窗/磁贴/横屏） · ${HostPackages.UI_HOST}", relaunchHome = false),
        Host(HostPackages.SYSTEM_UI, "系统界面", "QS 磁贴直点桥 · ${HostPackages.SYSTEM_UI}", relaunchHome = false),
    )

    data class Result(val ok: List<String>, val failed: List<String>) {
        val allOk: Boolean get() = failed.isEmpty()
    }

    /** IO 线程调用：逐宿主 force-stop，含 relaunchHome 的宿主在全部杀完后统一拉回 */
    fun restart(hosts: List<Host>): Result {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (host in hosts) {
            val done = runSu("am force-stop ${host.pkg}")
            if (done) ok += host.label else failed += host.label
        }
        // 桌面拉回：仅在 home 确实杀成功时才发（force-stop 后 stopped 态，HOME intent 显式解除）
        if (ok.any { label -> hosts.first { it.label == label }.relaunchHome }) {
            runSu("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
        }
        HLog.i("HostRestart", "restart done: ok=$ok failed=$failed")
        return Result(ok, failed)
    }

    /** su -c 单命令，带超时（0912 审查遗留的 waitFor 无超时问题，新代码不再复犯） */
    private fun runSu(command: String): Boolean = try {
        val proc = ProcessBuilder("su", "-c", command).start()
        val finished = proc.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroy()
            HLog.w("HostRestart", "su timeout: $command")
            false
        } else {
            proc.exitValue() == 0
        }
    } catch (e: Exception) {
        HLog.w("HostRestart", "su failed: $command: ${e.message}")
        false
    }
}
