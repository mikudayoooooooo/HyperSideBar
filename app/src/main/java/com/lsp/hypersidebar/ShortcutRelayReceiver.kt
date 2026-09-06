package com.lsp.hypersidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.DefaultLaunchStrategy
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutLauncher
import org.json.JSONObject

private const val TAG = "ShortcutRelay"

/**
 * :ui → 模块 App 的快捷方式 root 代发接收器（§2.4 实测定案）。
 *
 * :ui（system uid）对非 exported 目标 startActivityAsUser 静默假成功（不抛异常、
 * 实际不启动），且无 su 授权；本进程持 root（su am start 可启动非导出组件——
 * 编辑页测试启动的 root 链路已验证）。:ui 预检失败时把完整 ShortcutAction JSON
 * 广播过来，本接收器走完整启动链（validate 失败 → 直试 → ANF → su root）。
 *
 * 防伪：运行期随机令牌校验（[RelayToken]，remotePrefs 分发）——否则任意 App 可伪造
 * 广播借 root am start 启动任意组件。原硬编码令牌反编译即可读出，等同零校验（批次 0 修复）。
 * 令牌在本进程由 MainActivity/AllAppsActivity 同步 remotePrefs 时生成并缓存；缓存为空
 * （模块 App 从未启动过）时一律拒绝——root 代发是最坏场景，宁可拒绝不可放行。
 * 该拒收影响面极小：用户没打开过 App 就不存在已配置的快捷方式可代发。
 */
class ShortcutRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // manifest 快捷方式桥应答（launcher 进程查询结果，数据仅驱动选择器展示）
            PrefKeys.MANIFEST_SHORTCUTS_REPLY -> {
                val json = intent.getStringExtra(PrefKeys.MANIFEST_SHORTCUTS_EXTRA) ?: return
                com.lsp.hypersidebar.ui.settings.ManifestShortcutsBridge.onReply(context, json)
                return
            }
            PrefKeys.RELAY_LAUNCH_ACTION -> Unit
            else -> return
        }
        if (!RelayToken.verifyRelay(intent)) return
        // 链路追踪（util/Trace）：relay 意图自带呼出链 id，写入本进程供日志前缀
        val trace = intent.getStringExtra(com.lsp.hypersidebar.util.Trace.EXTRA)
        com.lsp.hypersidebar.util.Trace.current = trace
        val json = intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_SHORTCUT) ?: return
        val shortcut = runCatching {
            ShortcutAction.fromJson(JSONObject(json))
        }.getOrNull() ?: run {
            Log.w(TAG, "[${trace ?: "-"}] relay launch rejected: malformed shortcut json")
            return
        }

        // 启动含 binder/IO/su 子进程，goAsync + 后台线程避免 ANR
        val pending = goAsync()
        Thread {
            try {
                // 冷启动缺口修复（2026-09-05）：令牌缓存只在 Activity 绑定时填充——
                // 模块进程被杀后由本广播冷启时缓存为空，verifyRelay 一律拒绝（日志
                // 实证：磁贴/INTENT_URI 代发全静默失败）。后台线程内等桥绑定拉取
                // remotePrefs 并同步令牌（≤3s；LSPosed 死则超时按拒绝处理，安全档不变）
                if (com.lsp.hypersidebar.util.RelayToken.current() == null) {
                    val provisioned = awaitTokenProvision()
                    Log.i(TAG, "[${trace ?: "-"}] relay token cold-provision: ok=$provisioned")
                }
                Log.i(TAG, "[${trace ?: "-"}] relay launch: id=${shortcut.id} kind=${shortcut.kind}")
                val result = ShortcutLauncher.launch(context, shortcut, DefaultLaunchStrategy())
                Log.i(TAG, "[${trace ?: "-"}] relay launch result: $result")

                // 结果回执（2026-09-05）：①写 remotePrefs 供自检报告读取（远程排障
                // 无需 adb）；②回告 :ui——失败 toast 到前台，成功静默
                val ok = result is com.lsp.hypersidebar.util.LaunchResult.Success
                val reason = (result as? com.lsp.hypersidebar.util.LaunchResult.Failure)
                    ?.let { "${it.reason}: ${it.detail}" } ?: ""
                val record = "trace=$trace|label=${shortcut.label}|ok=$ok|reason=$reason|ts=${System.currentTimeMillis()}"
                runCatching {
                    com.lsp.hypersidebar.util.RemotePrefsBridge.prefs?.edit()
                        ?.putString(PrefKeys.LAST_RELAY_RESULT, record)?.apply()
                }
                runCatching {
                    val reply = Intent(PrefKeys.ACTION_RELAY_RESULT)
                        .setPackage("com.miui.securitycenter")
                        .putExtra("ok", ok)
                        .putExtra("label", shortcut.label)
                        .putExtra("reason", reason)
                        .putExtra(com.lsp.hypersidebar.util.Trace.EXTRA, trace)
                    com.lsp.hypersidebar.util.RelayToken.attach(
                        reply, com.lsp.hypersidebar.util.RelayToken.current()
                    )
                    context.sendBroadcast(reply)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * 冷启动等令牌：RemotePrefsBridge.addListener 幂等——已绑定立即同步回调，
     * 未绑定走异步绑定（onServiceBind 线程内 RelayToken.sync 填充缓存后才通知）。
     * 超时=绑定不可用，返回 false 交由 verifyRelay 按无令牌拒绝。
     */
    private fun awaitTokenProvision(): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        com.lsp.hypersidebar.util.RemotePrefsBridge.addListener { _ -> latch.countDown() }
        return latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }
}
