package com.lsp.hypersidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.DefaultLaunchStrategy
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
 * 防伪：令牌校验（PrefKeys.RELAY_LAUNCH_TOKEN，两侧同源共享）——否则任意 App
 * 可伪造广播借 root am start 启动任意组件。
 */
class ShortcutRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PrefKeys.RELAY_LAUNCH_ACTION) return
        if (intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN) != PrefKeys.RELAY_LAUNCH_TOKEN) {
            Log.w(TAG, "relay launch rejected: bad token")
            return
        }
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
