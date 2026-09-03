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
        if (intent.action != PrefKeys.RELAY_LAUNCH_ACTION) return
        if (!RelayToken.verifyRelay(intent)) return
        val json = intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_SHORTCUT) ?: return
        val shortcut = runCatching {
            ShortcutAction.fromJson(JSONObject(json))
        }.getOrNull() ?: run {
            Log.w(TAG, "relay launch rejected: malformed shortcut json")
            return
        }

        // 启动含 binder/IO/su 子进程，goAsync + 后台线程避免 ANR
        val pending = goAsync()
        Thread {
            try {
                Log.i(TAG, "relay launch: id=${shortcut.id} kind=${shortcut.kind}")
                val result = ShortcutLauncher.launch(context, shortcut, DefaultLaunchStrategy())
                Log.i(TAG, "relay launch result: $result")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
