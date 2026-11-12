package com.lsp.hypersidebar.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.FanLaunchStrategy
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.LaunchResult
import com.lsp.hypersidebar.util.NativeGateBridge
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutLauncher
import com.lsp.hypersidebar.util.ShortcutRelayBridge
import com.lsp.hypersidebar.util.Trace
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.HLog

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
    private val shouldSimulateRelayDead: () -> Boolean = { false },
    /** 跨进程防伪令牌（批次 0 安全修复）：取 launcher 侧 remotePrefs 读到的值，未下发则 null */
    private val relayToken: () -> String? = { null }
) : FanLaunchStrategy {

    override fun launchFreeform(context: Context, pkg: String, source: String?) {
        sendToRelay(context, "freeform pkg=$pkg") {
            putExtra(PrefKeys.FAN_EXTRA_PKG, pkg)
            source?.let { putExtra(PrefKeys.FAN_EXTRA_SOURCE, it) }
        }
    }

    override fun launchAllApps(context: Context, source: String?) {
        sendToRelay(context, "allApps") {
            putExtra(PrefKeys.FAN_EXTRA_ALL_APPS, true)
            source?.let { putExtra(PrefKeys.FAN_EXTRA_SOURCE, it) }
        }
    }

    override fun launchShortcut(context: Context, shortcut: ShortcutAction) {
        // 2026-11-12：快捷方式 root 代发改走 bindService（不再广播冷启模块 App）——
        // 广播路径下模块 App 约 5s 就被 SmartPower 冻结，首次点击必静默失败。
        // bind 失败才回退旧广播（兼容未更新模块/服务不可用）。
        val appCtx = context.applicationContext
        val token = relayToken()
        Thread {
            // OS2/OS3 的 launcher 自己有 Java startShortcut 桥：SHORTCUT_ID 先本地代发，
            // 不经过模块 App/root（快，且不受 SmartPower 冻结影响）。失败才走 Binder 代发。
            if (shortcut.kind == ShortcutKind.SHORTCUT_ID &&
                !runCatching { NativeGateBridge.isOs4NativeLauncher() }.getOrDefault(false)
            ) {
                val local = ShortcutLauncher.launchShortcutId(
                    appCtx, shortcut, token, allowRootFallback = false,
                    launcherBridgeAbsent = false
                )
                if (local is LaunchResult.Success) {
                    Handler(Looper.getMainLooper()).post {
                        runCatching {
                            Toast.makeText(
                                appCtx, "已启动：${shortcut.label}", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return@Thread
                }
                HLog.w(TAG, "launcher local startShortcut failed, fallback relay: $local")
            }
            val outcome = ShortcutRelayBridge.launchBlocking(appCtx, shortcut, token)
            if (outcome == null) {
                HLog.w(TAG, "shortcut bind unavailable, fallback broadcast: id=${shortcut.id}")
                sendToRelay(context, "shortcut id=${shortcut.id}") {
                    putExtra(PrefKeys.FAN_EXTRA_SHORTCUT, shortcut.toJson().toString())
                }
                return@Thread
            }
            onRelayResult?.invoke(true, "shortcut id=${shortcut.id}")
            // timeout 只说明没等到回执，服务可能仍在跑；未明确失败不 toast，避免误报。
            if (!outcome.handled) {
                HLog.w(TAG, "shortcut relay no reply yet: ${outcome.reason}")
                return@Thread
            }
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    val text = if (outcome.ok) {
                        "已启动：${shortcut.label}"
                    } else {
                        "无法启动：${shortcut.label}（${outcome.reason.take(80)}）"
                    }
                    Toast.makeText(appCtx, text, Toast.LENGTH_LONG).show()
                }.onFailure { HLog.w(TAG, "shortcut toast failed: ${it.message}") }
            }
        }.start()
    }

    override fun openNativePanel(context: Context) {
        // 面板打开经 :ui 的 FreeformRelayHook 转发到 DirectLaunchStrategy.openNativePanel
        sendToRelay(context, "openPanel") { putExtra(PrefKeys.FAN_EXTRA_OPEN_PANEL, true) }
    }

    private inline fun sendToRelay(
        context: Context,
        what: String,
        crossinline configure: Intent.() -> Unit
    ) {
        if (shouldSimulateRelayDead()) {
            HLog.w(TAG, "relay blackholed (debug switch): $what")
            handleRelayDead(context, what)
            return
        }
        val intent = Intent(launchAction).apply {
            setPackage(HostPackages.UI_HOST)
            configure()
            // 链路追踪（util/Trace）：单次呼出的 id 随 relay 过 :ui，日志按 id 串全链
            Trace.current?.let { putExtra(Trace.EXTRA, it) }
            RelayToken.attach(this, relayToken())
        }
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, result: Intent?) {
                    val code = resultCode
                    if (code == PrefKeys.PROBE_CODE_TOKEN_MISMATCH) {
                        // 令牌拒绝 ≠ 执行端死亡（M05）：单独提示，不进熔断计数。
                        HLog.w(TAG, "relay token mismatch: $what（不计 dead）")
                        onRelayResult?.invoke(true, what)
                        runCatching {
                            Toast.makeText(
                                c,
                                "配置令牌不同步，请重启安全中心或手机",
                                Toast.LENGTH_LONG
                            ).show()
                        }.onFailure { HLog.w(TAG, "token mismatch toast failed: ${it.message}") }
                        return
                    }
                    if (code != 0) {
                        HLog.i(TAG, "relay alive: $what delivered")
                        onRelayResult?.invoke(true, what)
                        return
                    }
                    HLog.e(TAG, "relay DEAD: $what not delivered (result code untouched)")
                    handleRelayDead(c, what)
                }
            },
            Handler(Looper.getMainLooper()),
            0, null, null
        )
        HLog.i(TAG, "relay broadcast sent (ordered): $what")
    }

    private fun handleRelayDead(context: Context, what: String) {
        onRelayResult?.invoke(false, what)
        runCatching {
            // PRD §9.4 字面措辞
            Toast.makeText(
                context,
                "服务不可用，请检查侧边栏开关",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
