package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.FanLaunchStrategy
import com.lsp.hypersidebar.util.FailureReason
import com.lsp.hypersidebar.util.FreeformLauncher
import com.lsp.hypersidebar.util.LaunchResult
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutLauncher
import com.lsp.hypersidebar.util.SystemLaunchStrategy

private const val TAG = "FanLaunch"

/**
 * securitycenter:ui 进程的直调策略。
 * 打开原生面板时经 PanelHideState 短暂隐藏 dock（hookDockLayoutVisibility 消费），5s 后恢复。
 */
class DirectLaunchStrategy : FanLaunchStrategy {

    override fun launchFreeform(context: Context, pkg: String) {
        FreeformLauncher.launch(context, pkg)
    }

    override fun launchAllApps(context: Context) {
        // 面板数据在启动时经 intent 传递：:ui 是 system uid，getFreeformSuggestionList
        // 反射可用；模块进程是普通 App，被 hidden API blocklist 拒绝（实测 denied），
        // 面板进程内的 DataLoader 永远拿不到建议列表
        val list = ArrayList(com.lsp.hypersidebar.util.DataLoader.loadApps(context))
        FreeformLauncher.launchSelfFreeform(
            context,
            com.lsp.hypersidebar.ui.allapps.AllAppsActivity::class.java
        ) { intent ->
            intent.putStringArrayListExtra(
                com.lsp.hypersidebar.ui.allapps.AllAppsActivity.EXTRA_SUGGESTIONS, list
            )
        }
    }

    override fun openNativePanel(context: Context) {
        PanelHideState.hidden.set(true)
        val intent = Intent("com.miui.gamebooster.PANNEL_OPEN").apply {
            setPackage("com.miui.securitycenter")
        }
        context.sendBroadcast(intent, "com.miui.gamebooster.permission.PANNEL_OPEN")
        Log.i(TAG, "openNativePanel: broadcast sent")
        Handler(Looper.getMainLooper()).postDelayed({
            PanelHideState.hidden.set(false)
        }, 5000)
    }

    override fun launchShortcut(context: Context, shortcut: ShortcutAction) {
        // 非 exported 目标预检失败时直接转发模块 App 代发（§2.4 实测定案）：
        // 本进程（system uid）startActivityAsUser 对启动不了的目标静默假成功
        // （不抛异常、实际不启动，无法靠异常触发 root 回退），且本进程无 su 授权；
        // 模块 App 进程持 root，其 validate→直试→ANF→su 链路已被编辑页测试验证。
        if (shortcut.kind != ShortcutKind.SERVICE) {
            val check = ShortcutLauncher.validate(context, shortcut)
            if (check is LaunchResult.Failure &&
                (check.reason == FailureReason.ACTIVITY_NOT_FOUND ||
                    check.reason == FailureReason.NOT_EXPORTED)
            ) {
                if (relayLaunchToModule(context, shortcut)) return
                Log.w(TAG, "relay launch to module app failed, falling back to local launch")
            }
        }

        val result = ShortcutLauncher.launch(context, shortcut, SystemLaunchStrategy())
        // service 拉起无界面反馈，toast 显式提醒（PRD §7.3.2）
        if (shortcut.kind == ShortcutKind.SERVICE && result is LaunchResult.Success) {
            runCatching {
                Toast.makeText(context, "已拉起服务：${shortcut.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** :ui → 模块 App root 代发：完整 ShortcutAction JSON 随广播携带（接收端无需读 prefs）。 */
    private fun relayLaunchToModule(context: Context, shortcut: ShortcutAction): Boolean {
        return runCatching {
            val intent = Intent(PrefKeys.RELAY_LAUNCH_ACTION).apply {
                setPackage(FreeformLauncher.MODULE_PACKAGE)
                putExtra(
                    PrefKeys.RELAY_LAUNCH_EXTRA_SHORTCUT,
                    shortcut.toJson().toString()
                )
                putExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN, PrefKeys.RELAY_LAUNCH_TOKEN)
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "relay launch to module app sent: id=${shortcut.id} kind=${shortcut.kind}")
            true
        }.getOrElse {
            Log.e(TAG, "relay launch to module app failed", it)
            false
        }
    }
}
