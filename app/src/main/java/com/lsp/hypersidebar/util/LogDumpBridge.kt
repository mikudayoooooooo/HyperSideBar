package com.lsp.hypersidebar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.lsp.hypersidebar.prefs.PrefKeys

/**
 * 日志拉取回传端（迭代六 §11.2，hook 进程侧）。
 *
 * 与 manifest 快捷方式桥同模式：模块 App（日志页/自检 v2）发 [PrefKeys.LOG_DUMP_REQUEST]
 * 广播，本进程接收器把 HLog 环形缓冲 JSON + 进程状态快照（熔断等）以
 * [PrefKeys.LOG_DUMP_REPLY] 定向回传。模块 App 前台=活进程，必收。
 *
 * 注册点：EdgeGestureHook（launcher）/FreeformRelayHook（:ui）的 Application.attach，
 * 与探针接收器同款生命周期。
 */
object LogDumpBridge {

    /**
     * @param statusProvider 进程状态快照（JSON 字符串，可 null）——launcher/:ui 传
     *   CircuitBreaker.snapshot()，模块 App 不注册本接收器（直读本地缓冲）
     * @param statsProvider 使用数据聚合 JSON（§11.3，可 null）——launcher/:ui 传
     *   StatsRecorder.dump()
     */
    fun register(
        context: Context,
        statusProvider: () -> String? = { null },
        statsProvider: () -> String? = { null }
    ) {
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val reply = Intent(PrefKeys.LOG_DUMP_REPLY)
                            .setPackage(FreeformLauncher.MODULE_PACKAGE)
                            .putExtra(PrefKeys.LOG_DUMP_EXTRA_PROC, HLog.proc())
                            .putExtra(PrefKeys.LOG_DUMP_EXTRA_LOGS, HLog.dumpJson())
                        statusProvider()?.let {
                            reply.putExtra(PrefKeys.LOG_DUMP_EXTRA_STATUS, it)
                        }
                        statsProvider()?.let {
                            reply.putExtra(PrefKeys.LOG_DUMP_EXTRA_STATS, it)
                        }
                        runCatching { c.sendBroadcast(reply) }
                            .onFailure { HLog.w("LogDump", "reply send failed: ${it.message}") }
                    }
                },
                IntentFilter(PrefKeys.LOG_DUMP_REQUEST),
                Context.RECEIVER_EXPORTED
            )
            HLog.i("LogDump", "log dump receiver registered")
        }.onFailure { HLog.w("LogDump", "register failed: ${it.message}") }
    }
}
