package com.lsp.hypersidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.PrefsFiles
import com.lsp.hypersidebar.util.DefaultLaunchStrategy
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutLauncher
import org.json.JSONObject
import com.lsp.hypersidebar.util.HLog

private const val TAG = "ShortcutRelay"

/**
 * :ui → 模块 App 的快捷方式 root 代发接收器（§2.4 实测定案）。
 *
 * :ui（平台签名特权，独立 uid 非 1000）对非 exported 目标 startActivityAsUser 静默假成功（不抛异常、
 * 实际不启动），且无 su 授权；本进程持 root（su am start 可启动非导出组件——
 * 编辑页测试启动的 root 链路已验证）。:ui 预检失败时把完整 ShortcutAction JSON
 * 广播过来，本接收器走完整启动链（validate 失败 → 直试 → ANF → su root）。
 *
 * 防伪：运行期随机令牌校验（[RelayToken]，remotePrefs 分发）——否则任意 App 可伪造
 * 广播借 root am start 启动任意组件。原硬编码令牌反编译即可读出，等同零校验（批次 0 修复）。
 * 令牌在 remotePrefs 分发；模块进程被广播冷启、内存缓存为空时，先等 LSPosed 服务绑定
 * 补挂（≤3s，[RelayToken.awaitAndVerify]），补挂失败则按拒绝处理（root 代发是最坏场景，
 * 宁可拒绝不可放行）。鉴权一律排在一切副作用（Trace 写入、JSON 解析）之前。
 *
 * 分档（2026-11-02 审查 P2 定案）：launch 与 manifest 清单应答**同一把令牌闸**
 * （[RelayToken.awaitAndVerify]）。清单应答原设想按发送方包名收窄，但该 API 在本机
 * 跨应用广播上实测为 null（伪造回包被放行并落盘）⇒ 不可用；改为验令牌后，两个合法
 * 发送方（launcher/:ui 的 hook 侧）本就随回包 `RelayToken.attach`，零成本。
 */
class ShortcutRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // manifest 快捷方式桥应答（launcher 进程查询结果，数据仅驱动选择器展示）
            PrefKeys.MANIFEST_SHORTCUTS_REPLY -> {
                handleReply(context, intent)
                return
            }
            // QS 磁贴解冻 relay 现走 bind 通道（2026-09-07 方案 2）：UnfreezeRelayService
            // （bind 唤醒冻结的模块 App 后 su 解冻），广播通道不再承担解冻。
            // 本接收器仅保留 LAUNCH 代发与 manifest 桥应答。
            PrefKeys.RELAY_LAUNCH_ACTION -> Unit
            else -> return
        }
        // 只做免费的类型检查；解析与鉴权都推到后台线程（鉴权必须排在副作用之前）
        val json = intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_SHORTCUT) ?: return

        // 启动含 binder/IO/su 子进程，goAsync + 后台线程避免 ANR
        val pending = goAsync()
        Thread {
            try {
                val mirror = context.getSharedPreferences(PrefsFiles.APP_LOCAL, Context.MODE_PRIVATE)
                RelayToken.primeFromMirror(mirror)
                // 冷启动补挂（2026-11-02 审查 P1 重排）：原实现的补挂排在 verifyRelay 的
                // `return` 之后 ⇒ 流程图里那段代码是死的。现在先补挂再严格比对；
                // 补挂失败（LSPosed 桥不可用）仍按无令牌拒绝，安全档不变。
                if (!RelayToken.awaitAndVerify(intent)) return@Thread
                RelayToken.saveMirror(mirror)
                // 链路追踪（util/Trace）：relay 意图自带呼出链 id，写入本进程供日志前缀。
                // 鉴权之后才写——伪造广播不得污染本进程的诊断上下文。
                val trace = intent.getStringExtra(com.lsp.hypersidebar.util.Trace.EXTRA)
                com.lsp.hypersidebar.util.Trace.current = trace
                val shortcut = runCatching {
                    ShortcutAction.fromJson(JSONObject(json))
                }.getOrNull() ?: run {
                    HLog.w(TAG, "[${trace ?: "-"}] relay launch rejected: malformed shortcut json")
                    return@Thread
                }
                HLog.i(TAG, "[${trace ?: "-"}] relay launch: id=${shortcut.id} kind=${shortcut.kind}")
                val noBridge = intent.getBooleanExtra(PrefKeys.RELAY_LAUNCH_EXTRA_NO_BRIDGE, false)
                val result = ShortcutLauncher.launch(
                    context, shortcut, DefaultLaunchStrategy(),
                    launcherBridgeAbsent = noBridge
                )
                HLog.i(TAG, "[${trace ?: "-"}] relay launch result: $result")

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
                        .setPackage(HostPackages.UI_HOST)
                        .putExtra(PrefKeys.RELAY_RESULT_EXTRA_OK, ok)
                        .putExtra(PrefKeys.RELAY_RESULT_EXTRA_LABEL, shortcut.label)
                        .putExtra(PrefKeys.RELAY_RESULT_EXTRA_REASON, reason)
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
     * manifest 清单应答（2026-11-02 审查 P2）：原先零校验即落盘，任意 App 一条广播就能
     * 污染选择器的快捷方式目录（真机实测：`com.android.shell` 发的伪造回包被接受并写入
     * `manifest_shortcuts_cache`）。
     *
     * 定案=**令牌档**，与 launch/restore 同一把闸：
     * - 两个合法发送方（launcher 的 `EdgeGestureHook`、:ui 的 `FreeformRelayHook`）本来就
     *   在回包里 `RelayToken.attach`，所以令牌闸对它们零成本；
     * - 原打算用的"发送方包名/uid"在本机 OS4/API 36 上对跨应用广播实测为 null（见上一条
     *   实测），恒 fail-open ⇒ 不可用，已放弃。
     * 载荷上限先于鉴权检查（免费），鉴权在后台线程（冷启动要等桥绑定 ≤3s），落盘回主线程。
     */
    private fun handleReply(context: Context, intent: Intent) {
        val json = intent.getStringExtra(PrefKeys.MANIFEST_SHORTCUTS_EXTRA) ?: return
        if (json.length > MAX_SHORTCUT_PAYLOAD_CHARS) {
            HLog.w(TAG, "manifest reply rejected: payload ${json.length}B")
            return
        }
        val pending = goAsync()
        Thread {
            try {
                if (!RelayToken.awaitAndVerify(intent)) return@Thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    com.lsp.hypersidebar.ui.settings.ManifestShortcutsBridge.onReply(context, json)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        /** 清单应答载荷上限（懒加载清单实测 ~KB 级；上限只为拦住病态/投毒载荷） */
        const val MAX_SHORTCUT_PAYLOAD_CHARS = 256 * 1024
    }
}
