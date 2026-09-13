package com.lsp.hypersidebar.ui.settings

import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.util.HLog
import java.util.concurrent.TimeUnit

/**
 * 调试区「重启 hook 宿主」（0912）：模块更新后"三宿主全重启"运维规程的一键化
 * （漏 SystemUI=磁贴成功率回退，2026-09-06 实锤）。目标仅限本模块 hook 的三个宿主，
 * 包名全部取自 [HostPackages] 常量（无外部输入，无注入面）。
 *
 * 各宿主杀法不同（0913 定案，对齐 HyperCeiler RestartAlertDialog→AppsTool.killApps 的
 * 实测效果）：
 * - home：`am force-stop`（非 persistent，杀后发 HOME intent 显式拉回）；
 * - securitycenter：必须整包 `am force-stop`——:ui 是 ":ui" 后缀进程，pidof 按精确名
 *   够不到它，信号杀会漏；系统在呼出侧边栏时拉起 :ui，hook 随进程重注入；
 * - systemui：SIGTERM 信号杀（`pidof` + `kill -s 15`，与 HyperCeiler 同信号）——
 *   force-stop 在 HyperOS 上重启过程无黑屏、无法肉眼确认 hook 是否重载；信号杀走
 *   "进程先死 → AMS AppDied → persistent 自启"，黑屏一闪即重启成功的可观测标志。
 *   pidof 精确名匹配（本机 unfreeze 链路已验证可用），hook 仅在 SystemUI 主进程，
 *   无须处理子进程。
 */
internal object HostRestarter {

    /** 单条命令预算（su 冷授权可能停在授权弹窗，超时放弃该宿主并计入失败） */
    private const val COMMAND_TIMEOUT_MS = 8_000L

    data class Host(
        val pkg: String,
        val label: String,
        val summary: String,
        /** su -c 执行的杀进程命令（pkg 为常量拼接，无注入面） */
        val killCommand: String,
        val relaunchHome: Boolean
    )

    val HOSTS = listOf(
        Host(
            HostPackages.HOME, "系统桌面", "边缘手势触发端 · ${HostPackages.HOME}",
            killCommand = "am force-stop ${HostPackages.HOME}",
            relaunchHome = true
        ),
        Host(
            HostPackages.UI_HOST, "手机管家", ":ui 执行端（小窗/磁贴/横屏） · ${HostPackages.UI_HOST}",
            killCommand = "am force-stop ${HostPackages.UI_HOST}",
            relaunchHome = false
        ),
        Host(
            HostPackages.SYSTEM_UI, "系统界面", "QS 磁贴直点桥 · ${HostPackages.SYSTEM_UI}",
            killCommand = "pids=\$(pidof ${HostPackages.SYSTEM_UI}); " +
                "for p in \$pids; do kill -s 15 \$p; done",
            relaunchHome = false
        ),
    )

    data class Result(val ok: List<String>, val failed: List<String>) {
        val allOk: Boolean get() = failed.isEmpty()
    }

    /** IO 线程调用：逐宿主执行 [Host.killCommand]，含 relaunchHome 的宿主杀完后统一拉回 */
    fun restart(hosts: List<Host>): Result {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (host in hosts) {
            val done = runSu(host.killCommand)
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
