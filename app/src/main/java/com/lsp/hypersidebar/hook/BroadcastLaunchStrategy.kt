package com.lsp.hypersidebar.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lsp.hypersidebar.ui.fan.FanLaunchStrategy
import com.lsp.hypersidebar.util.ShortcutAction

private const val TAG = "FanLaunch"

/**
 * com.miui.home（launcher）进程的广播策略：执行动作转发给 securitycenter:ui。
 * 广播 action 与 :ui 侧 FreeformRelayHook 注册的接收器对应。
 *
 * :ui 存活探测（1C，PRD §9.4 ":ui 进程不存活 → toast"）：全部转发走有序广播，
 * 初始 code=0，:ui 中继接收器收到即置 1；最终回调读 0 = :ui 已死或接收器未注册
 * （典型场景：用户关掉系统侧边栏开关）→ toast 提示 + [onRelayResult] 上报
 * （熔断器数据源，连续 5 次失联 = 机制性失败）。"尝试拉起"未实现——
 * :ui 内无可验证的启动入口 service，凭空发明违反纪律，PRD 场景由提示替代。
 */
class BroadcastLaunchStrategy(
    private val launchAction: String,
    private val onRelayResult: ((alive: Boolean, what: String) -> Unit)? = null,
    /** 调试开关（熔断链路验证用）：真机 :ui 死亡窗口太短（~10s 即被系统重绑），无法自然复现 */
    private val shouldSimulateRelayDead: () -> Boolean = { false }
) : FanLaunchStrategy {

    override fun launchFreeform(context: Context, pkg: String) {
        sendToRelay(context, "freeform pkg=$pkg") { putExtra("pkg", pkg) }
    }

    override fun launchAllApps(context: Context) {
        sendToRelay(context, "allApps") { putExtra("allApps", true) }
    }

    override fun launchShortcut(context: Context, shortcut: ShortcutAction) {
        sendToRelay(context, "shortcut id=${shortcut.id}") {
            putExtra("shortcut", shortcut.toJson().toString())
        }
    }

    override fun openNativePanel(context: Context) {
        // 面板打开经 :ui 的 FreeformRelayHook 转发（其负责 PanelHideState 的设置与恢复）
        sendToRelay(context, "openPanel") { putExtra("openPanel", true) }
    }

    private inline fun sendToRelay(
        context: Context,
        what: String,
        crossinline configure: Intent.() -> Unit
    ) {
        if (shouldSimulateRelayDead()) {
            Log.w(TAG, "relay blackholed (debug switch): $what")
            handleRelayDead(context, what)
            return
        }
        val intent = Intent(launchAction).apply {
            setPackage("com.miui.securitycenter")
            configure()
        }
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, result: Intent?) {
                    if (resultCode != 0) {
                        Log.i(TAG, "relay alive: $what delivered")
                        onRelayResult?.invoke(true, what)
                        return
                    }
                    Log.e(TAG, "relay DEAD: $what not delivered (result code untouched)")
                    handleRelayDead(c, what)
                }
            },
            Handler(Looper.getMainLooper()),
            0, null, null
        )
        Log.i(TAG, "relay broadcast sent (ordered): $what")
    }

    private fun handleRelayDead(context: Context, what: String) {
        onRelayResult?.invoke(false, what)
        runCatching {
            Toast.makeText(
                context,
                "扇形执行端不可用：请检查系统侧边栏开关",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
