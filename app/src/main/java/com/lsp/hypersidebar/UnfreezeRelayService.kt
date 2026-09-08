package com.lsp.hypersidebar

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.RelayToken

private const val TAG = "UnfreezeRelay"

/**
 * :ui → 模块 App 的解冻 relay 服务（2026-09-07 方案 2：bind 唤醒）。
 *
 * 前身是广播 relay（RELAY_UNFREEZE_*，随预热制退役）：模块 App 被 SmartPower
 * 冻结时广播被静默丢弃（12:00 实证 resultCode=0、10ms 秒回、无进程启动），
 * 只能靠省电白名单续命。改走 bindService：bind 请求经 AMS/SmartPower，
 * uid 1000 调用方触发 GreezeManager THAW（08:52:27 磁贴侧同款规则实证）——
 * 冻结的模块 App 被 bind 唤醒后 su 才能执行，死进程则被 bind 冷启动，
 * 全程无须保活/白名单。
 *
 * 防伪（与广播 relay 同档严格）：Binder.getCallingUid()==SYSTEM_UID +
 * 运行期随机令牌（[RelayToken]，remotePrefs 分发）双重校验——exported 服务
 * 不得给任意调用方执行 su。
 */
class UnfreezeRelayService : Service() {

    companion object {
        /** 请求：data 带 pkg / token。应答（replyTo）arg1=1 su 已执行 / 0 失败 */
        const val MSG_UNFREEZE = 1

        /** :ui 宿主包名（动态解析其 uid 做 ACL，勿硬编码 uid——HyperOS 3 实测 10613 非 system） */
        private const val UI_HOST_PKG = "com.miui.securitycenter"
    }

    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("UnfreezeRelay").apply { start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_UNFREEZE) return
                // Messenger 服务端是异步投递（sendMessage→looper），handleMessage 已脱离
                // binder 事务上下文——Binder.getCallingUid() 此处恒返回本进程 uid
                //（0907 23:12 实测=模块自身 10613 被误当调用方，两版 ACL 全拒）；调用方
                // uid 必须读 sendingUid（Messenger 发送端由框架填充，专为异步设计）
                val callerUid = msg.sendingUid
                val ok = handleUnfreeze(callerUid, msg.data)
                runCatching {
                    msg.replyTo?.send(Message.obtain(null, 0).apply { arg1 = if (ok) 1 else 0 })
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun isUiHostUid(uid: Int): Boolean =
        runCatching {
            packageManager.getPackagesForUid(uid)?.contains(UI_HOST_PKG) == true
        }.getOrDefault(false)

    private fun handleUnfreeze(callerUid: Int, data: Bundle?): Boolean {
        val pkg = data?.getString("pkg") ?: return false
        // ① 调用方 uid：仅接受 system 或 :ui 宿主（com.miui.securitycenter）。
        // Binder uid 由内核强制不可伪造；uid 动态解析（0907 修正史：先误设 SYSTEM_UID
        // 单检、再误读 10613 为 :ui——10613 实为模块 App 自身 uid，:ui 真实 uid 未测得，
        // 动态解析杜绝再猜）
        if (callerUid != Process.SYSTEM_UID && !isUiHostUid(callerUid)) {
            Log.w(TAG, "unfreeze rejected: caller uid=$callerUid (pkg=$pkg)")
            return false
        }
        // ② 包名白名单（su 脚本唯一注入面）
        if (!pkg.matches(Regex("[A-Za-z0-9._]+"))) {
            Log.w(TAG, "unfreeze rejected: malformed pkg=$pkg")
            return false
        }
        // ③ 令牌（root 档=严格）：冷启动缓存为空时等桥绑定 ≤3s
        //（0907 实锤：漏等待=静默拒绝，解冻从未执行）
        val got = data.getString("token")
        if (RelayToken.current() == null) {
            val provisioned = RemotePrefsBridge.awaitTokenProvision()
            Log.i(TAG, "unfreeze token cold-provision: ok=$provisioned")
        }
        val expected = RelayToken.current()
        if (expected.isNullOrEmpty() || got.isNullOrEmpty() || got != expected) {
            Log.w(TAG, "unfreeze rejected: bad token (pkg=$pkg, provisioned=${!expected.isNullOrEmpty()})")
            return false
        }
        // 复合脚本整串作为单个 -c 参数裸传（su -c 引号坑，见 Intent URI 坑链）；
        // 多进程应用逐 pid 解冻；对未冻结进程写 0 为无害幂等
        val script = "pids=\$(pidof $pkg); for p in \$pids; do " +
            "u=\$(stat -c %u /proc/\$p 2>/dev/null); " +
            "[ -n \"\$u\" ] && echo 0 > /sys/fs/cgroup/apps/uid_\$u/pid_\$p/cgroup.freeze; " +
            "done"
        var ok = false
        var detail = ""
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            proc.waitFor()
            ok = proc.exitValue() == 0
            detail = "out=[${out.trim()}] err=[${err.trim()}]"
        }.onFailure { detail = "exec failed: ${it.message}" }
        Log.i(TAG, "unfreeze $pkg: su ok=$ok $detail")
        return ok
    }
}
