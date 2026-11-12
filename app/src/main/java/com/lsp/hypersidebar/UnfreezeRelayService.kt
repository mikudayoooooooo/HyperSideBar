package com.lsp.hypersidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.DefaultLaunchStrategy
import com.lsp.hypersidebar.util.NativeGateBridge
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutLauncher
import com.lsp.hypersidebar.util.Trace
import org.json.JSONObject

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

        /**
         * 请求：data 带 shortcut JSON / token / noBridge；应答（replyTo）arg1=1 服务已处理，
         * data.ok=true/false + data.reason + data.label。
         *
         * 2026-11-12（真机实证）：模块 App 被广播冷启后 SmartPower 约 5s 就把进程冻结
         * （`freezeUid SUCCESS ... reason=new process`），`ShortcutRelayReceiver` 的
         * goAsync 线程还没跑完 su 就被冻住 => 第一次点击静默失败、很久后才报失败。
         * 改由调用方 bind 本服务：bind 连接把模块 App 提升为 bound 进程，su 期间不被冻。
         */
        const val MSG_LAUNCH_SHORTCUT = 2

        /** 调用方在发 MSG_LAUNCH_SHORTCUT 前 startForegroundService 的通知 action。 */
        const val ACTION_FOREGROUND_START = "com.lsp.hypersidebar.action.RELAY_FOREGROUND"
        private const val CHANNEL_ID = "hsb_relay"
        private const val NOTIFICATION_ID = 0x4853
    }

    private lateinit var messenger: Messenger
    private lateinit var thread: HandlerThread

    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("UnfreezeRelay").apply { start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_LAUNCH_SHORTCUT) {
                    val r = handleShortcutLaunch(msg.sendingUid, msg.data)
                    runCatching {
                        msg.replyTo?.send(Message.obtain(null, 0).apply {
                            arg1 = if (r.handled) 1 else 0
                            data = Bundle().apply {
                                putBoolean(PrefKeys.RELAY_RESULT_EXTRA_OK, r.ok)
                                putString(PrefKeys.RELAY_RESULT_EXTRA_REASON, r.reason)
                                putString(PrefKeys.RELAY_RESULT_EXTRA_LABEL, r.label)
                            }
                        })
                    }
                    // 启动完成后立刻撤掉前台标记；服务本身仍可被 bind 复用。
                    if (foregroundStarted) {
                        foregroundStarted = false
                        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    }
                    return
                }
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

    /**
     * 2026-11-12：快捷方式代发前由调用方 startForegroundService。模块 App 被 SmartPower
     * cgroup 冻结时，单纯 bind 只能把它抬回 background 几秒（真机第二轮 25s 超时）；
     * 前台服务是唯一能稳定把进程拉出冻结态的免 root 手段。启动完成后马上撤通知。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FOREGROUND_START) {
            runCatching { startRelayForeground() }
                .onFailure { HLog.w(TAG, "startForeground failed: ${it.message}") }
        }
        return START_NOT_STICKY
    }

    private fun startRelayForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.relay_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(getString(R.string.relay_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
        HLog.i(TAG, "relay foreground started")
    }

    /** 同 ConfigPullService：服务停止时收线程（2026-10-30 审计）。 */
    override fun onDestroy() {
        runCatching { thread.quitSafely() }
            .onFailure { android.util.Log.w(TAG, "quit failed: ${it.message}") }
        super.onDestroy()
    }

    private fun isUiHostUid(uid: Int): Boolean =
        runCatching {
            packageManager.getPackagesForUid(uid)?.contains(HostPackages.UI_HOST) == true
        }.getOrDefault(false)

    private data class ShortcutRelayResult(
        val handled: Boolean,
        val ok: Boolean,
        val reason: String,
        val label: String
    )

    /**
     * 2026-11-12：快捷方式 root 代发也走 bind（见 [MSG_LAUNCH_SHORTCUT]）。
     * 调用方=桌面（com.miui.home）/ :ui / SystemUI；uid 由内核强制，再叠运行期令牌。
     */
    private fun handleShortcutLaunch(callerUid: Int, data: Bundle?): ShortcutRelayResult {
        if (data == null) return ShortcutRelayResult(true, false, "missing data", "")
        val trace = data.getString(Trace.EXTRA)
        val label = data.getString(PrefKeys.RELAY_RESULT_EXTRA_LABEL) ?: ""
        if (callerUid != Process.SYSTEM_UID) {
            val pkgs = runCatching { packageManager.getPackagesForUid(callerUid) }.getOrNull().orEmpty()
            if (pkgs.none {
                    it == HostPackages.HOME || it == HostPackages.UI_HOST || it == HostPackages.SYSTEM_UI
                }
            ) {
                HLog.w(TAG, "[${trace ?: "-"}] shortcut relay rejected: caller uid=$callerUid")
                return ShortcutRelayResult(true, false, "caller uid=$callerUid", label)
            }
        }
        val json = data.getString(PrefKeys.RELAY_LAUNCH_EXTRA_SHORTCUT)
            ?: return ShortcutRelayResult(true, false, "missing shortcut json", label)
        val shortcut = runCatching { ShortcutAction.fromJson(JSONObject(json)) }.getOrNull()
            ?: return ShortcutRelayResult(true, false, "malformed shortcut json", label)
        // Binder 通道的凭证是内核 uid + 包名 ACL（见 ARCH_SIMPLIFICATION：Binder 化后
        // uid 即凭证）；令牌只在两侧都有值时做一致性日志，不作闸——首次安装模块 App 还
        // 没生成令牌时也能启动，不再先等 6s provision。
        val got = data.getString(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN)
        val expected = RelayToken.current()
        if (!expected.isNullOrEmpty() && !got.isNullOrEmpty() && got != expected) {
            HLog.w(TAG, "[${trace ?: "-"}] shortcut relay token mismatch (uid ACL ok)")
        }
        HLog.i(TAG, "[${trace ?: "-"}] shortcut relay: id=${shortcut.id} kind=${shortcut.kind}")
        val noBridge = data.getBoolean(PrefKeys.RELAY_LAUNCH_EXTRA_NO_BRIDGE, false) ||
            runCatching { NativeGateBridge.isOs4NativeLauncher() }.getOrDefault(false)
        val result = ShortcutLauncher.launch(
            this, shortcut, DefaultLaunchStrategy(),
            allowRootFallback = true, launcherBridgeAbsent = noBridge
        )
        val ok = result is com.lsp.hypersidebar.util.LaunchResult.Success
        val reason = (result as? com.lsp.hypersidebar.util.LaunchResult.Failure)
            ?.let { "${it.reason}: ${it.detail}" } ?: ""
        HLog.i(TAG, "[${trace ?: "-"}] shortcut relay result: $result")
        runCatching {
            RemotePrefsBridge.prefs?.edit()?.putString(
                PrefKeys.LAST_RELAY_RESULT,
                "trace=$trace|label=${shortcut.label}|ok=$ok|reason=$reason|ts=${System.currentTimeMillis()}"
            )?.apply()
        }.onFailure { HLog.w(TAG, "write LAST_RELAY_RESULT failed: ${it.message}") }
        return ShortcutRelayResult(true, ok, reason, shortcut.label)
    }

    private fun handleUnfreeze(callerUid: Int, data: Bundle?): Boolean {
        val pkg = data?.getString(PrefKeys.UNFREEZE_EXTRA_PKG) ?: return false
        // ① 调用方 uid：仅接受 system 或 :ui 宿主（com.miui.securitycenter）。
        // Binder uid 由内核强制不可伪造；uid 动态解析（0907 修正史：先误设 SYSTEM_UID
        // 单检、再误读 10613 为 :ui——10613 实为模块 App 自身 uid，:ui 真实 uid 未测得，
        // 动态解析杜绝再猜）
        if (callerUid != Process.SYSTEM_UID && !isUiHostUid(callerUid)) {
            HLog.w(TAG, "unfreeze rejected: caller uid=$callerUid (pkg=$pkg)")
            return false
        }
        // ② 包名白名单（su 脚本唯一注入面）
        if (!pkg.matches(Regex("[A-Za-z0-9._]+"))) {
            HLog.w(TAG, "unfreeze rejected: malformed pkg=$pkg")
            return false
        }
        // ③ 令牌（root 档=严格）：冷启动缓存为空时先读私有镜像，再等桥绑定 ≤6s
        //（0907 实锤：漏等待=静默拒绝，解冻从未执行）
        val mirror = getSharedPreferences(
            com.lsp.hypersidebar.prefs.PrefsFiles.APP_LOCAL, android.content.Context.MODE_PRIVATE
        )
        RelayToken.primeFromMirror(mirror)
        val got = data.getString(PrefKeys.UNFREEZE_EXTRA_TOKEN)
        // 仅镜像不算权威：镜像可能过期，必须等 remotePrefs 绑定后 sync 覆盖（最多 6s）。
        if (!RelayToken.authorityReady()) {
            val provisioned = RemotePrefsBridge.awaitTokenProvision(6)
            HLog.i(TAG, "unfreeze token cold-provision: ok=$provisioned")
        }
        val expected = RelayToken.current()
        if (expected.isNullOrEmpty() || got.isNullOrEmpty() || got != expected) {
            HLog.w(TAG, "unfreeze rejected: bad token (pkg=$pkg, provisioned=${!expected.isNullOrEmpty()})")
            return false
        }
        RelayToken.saveMirror(mirror)
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
            // use 确保异常路径也关闭管道，避免反复失败时泄漏 FD
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val err = proc.errorStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            ok = proc.exitValue() == 0
            detail = "out=[${out.trim()}] err=[${err.trim()}]"
        }.onFailure { detail = "exec failed: ${it.message}" }
        HLog.i(TAG, "unfreeze $pkg: su ok=$ok $detail")
        return ok
    }
}
