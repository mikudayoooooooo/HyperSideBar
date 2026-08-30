package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lsp.hypersidebar.ui.fan.FanLaunchStrategy
import com.lsp.hypersidebar.util.ShortcutAction

private const val TAG = "FanLaunch"

/**
 * com.miui.home（launcher）进程的广播策略：执行动作转发给 securitycenter:ui。
 * 广播 action 与 :ui 侧 FreeformRelayHook 注册的接收器对应。
 */
class BroadcastLaunchStrategy(
    private val launchAction: String
) : FanLaunchStrategy {

    override fun launchFreeform(context: Context, pkg: String) {
        val intent = Intent(launchAction).apply {
            setPackage("com.miui.securitycenter")
            putExtra("pkg", pkg)
        }
        context.sendBroadcast(intent)
        Log.i(TAG, "launchFreeform broadcast sent (pkg=$pkg)")
    }

    override fun launchAllApps(context: Context) {
        val intent = Intent(launchAction).apply {
            setPackage("com.miui.securitycenter")
            putExtra("allApps", true)
        }
        context.sendBroadcast(intent)
        Log.i(TAG, "launchAllApps broadcast sent")
    }

    override fun launchShortcut(context: Context, shortcut: ShortcutAction) {
        val intent = Intent(launchAction).apply {
            setPackage("com.miui.securitycenter")
            putExtra("shortcut", shortcut.toJson().toString())
        }
        context.sendBroadcast(intent)
        Log.i(TAG, "launchShortcut broadcast sent (id=${shortcut.id})")
    }

    override fun openNativePanel(context: Context) {
        // 面板打开经 :ui 的 FreeformRelayHook 转发（其负责 PanelHideState 的设置与恢复）
        val intent = Intent(launchAction).apply {
            setPackage("com.miui.securitycenter")
            putExtra("openPanel", true)
        }
        context.sendBroadcast(intent)
        Log.i(TAG, "openNativePanel: broadcast sent")
    }
}
