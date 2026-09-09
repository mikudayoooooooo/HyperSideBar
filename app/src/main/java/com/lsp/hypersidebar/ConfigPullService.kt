package com.lsp.hypersidebar

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.lsp.hypersidebar.util.RemotePrefsBridge

private const val TAG = "ConfigPull"

/**
 * 配置拉取服务（迭代六 §11.1，配对 ConfigPullBridge）。
 *
 * 背景：ConfigSync 的广播推送通道对被 SmartPower 冻结的宿主进程静默丢弃（QS 磁贴
 * saga 实证定律）——用户在设置页改配置时宿主必然后台（大概率冻结），SYNC 丢失后
 * 配置停留旧快照直到宿主重启。本服务提供可靠兜底：hook 侧 stale 时 bind 本服务，
 * bind 本身唤醒冻结/拉起死进程（UnfreezeRelayService 同定律），应答全量配置。
 *
 * 与广播推送的关系：推送是快路径（宿主活着时秒达），本服务只在 stale+节流下被拉，
 * 平时零流量；拉到后走同一个 ConfigSync.applySync 入口，读取方无感。
 *
 * 安全口径：配置内容非敏感（侧边栏观感，ConfigSync 广播同档——"伪造的代价是
 * 观感被改"），但仍限调用方 uid=system 或两个 hook 宿主包（com.miui.home /
 * com.miui.securitycenter），不对外开放。只读不写、无 su、无令牌（令牌是启动行为
 * 防伪用，配置读取不需要；冷启动时 remotePrefs 尚未绑定则等待 ≤3s 再应答）。
 */
class ConfigPullService : Service() {

    companion object {
        /** 请求拉取全量配置；应答（replyTo）arg1=1 成功（data.map=Serializable Map）/ 0 配置不可用 */
        const val MSG_PULL_CONFIG = 1

        private val HOST_PKGS = setOf("com.miui.home", "com.miui.securitycenter")
    }

    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("ConfigPull").apply { start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_PULL_CONFIG) return
                // 同 UnfreezeRelayService 的坑：handleMessage 已脱离 binder 事务上下文，
                // Binder.getCallingUid() 恒返回本进程 uid，调用方必须读 sendingUid
                val callerUid = msg.sendingUid
                val map = handlePull(callerUid)
                runCatching {
                    msg.replyTo?.send(Message.obtain(null, 0).apply {
                        arg1 = if (map != null) 1 else 0
                        if (map != null) data = Bundle().apply { putSerializable("map", HashMap(map)) }
                    })
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun isAllowedUid(uid: Int): Boolean =
        uid == Process.SYSTEM_UID || runCatching {
            packageManager.getPackagesForUid(uid)?.any { it in HOST_PKGS } == true
        }.getOrDefault(false)

    /** 返回全量配置 Map；null=调用方不合法或 remotePrefs 未绑定（含等待超时） */
    private fun handlePull(callerUid: Int): Map<String, Any>? {
        if (!isAllowedUid(callerUid)) {
            Log.w(TAG, "pull rejected: caller uid=$callerUid")
            return null
        }
        if (RemotePrefsBridge.prefs == null) {
            val bound = RemotePrefsBridge.awaitPrefsBind()
            Log.i(TAG, "pull cold-provision: bound=$bound")
        }
        val prefs = RemotePrefsBridge.prefs ?: return null
        val map = HashMap<String, Any>()
        for ((k, v) in prefs.all) {
            if (k != null && v != null) map[k] = v
        }
        Log.i(TAG, "pull served: ${map.size} keys")
        return map
    }
}
