package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lsp.hypersidebar.ui.fan.FanLaunchStrategy
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
        val result = ShortcutLauncher.launch(context, shortcut, SystemLaunchStrategy())
        // service 拉起无界面反馈，toast 显式提醒（PRD §7.3.2）
        if (shortcut.kind == ShortcutKind.SERVICE && result is LaunchResult.Success) {
            runCatching {
                Toast.makeText(context, "已拉起服务：${shortcut.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
