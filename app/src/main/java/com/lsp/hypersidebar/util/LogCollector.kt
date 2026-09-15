package com.lsp.hypersidebar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.mutableStateMapOf
import com.lsp.hypersidebar.prefs.PrefKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 日志采集器（迭代六 §11.2，模块 App 进程侧，配对 LogDumpBridge）。
 *
 * 日志页/自检 v2 的数据源：发 [PrefKeys.LOG_DUMP_REQUEST] 广播，各 hook 进程把
 * HLog 环形缓冲 JSON 定向回传；本对象按进程短名（launcher/ui/sys/app）归档到
 * Compose state 供页面直接渲染。模块 App 自身缓冲（proc="app"）不经广播、直读。
 */
object LogCollector {

    /** proc 短名 → 日志条目（时间升序） */
    val logs = mutableStateMapOf<String, List<HLog.Entry>>()

    /** proc 短名 → 进程状态快照 JSON（熔断等，可空缺） */
    val statuses = mutableStateMapOf<String, String>()

    /** proc 短名 → StatsRecorder 聚合 JSON（§11.3，可空缺） */
    val stats = mutableStateMapOf<String, String>()

    @Volatile private var registered = false

    /** 幂等注册（MainActivity/日志页进入时调用；模块进程内只需一次） */
    fun register(context: Context) {
        if (registered) return
        registered = true
        runCatching {
            context.applicationContext.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val proc = intent.getStringExtra(PrefKeys.LOG_DUMP_EXTRA_PROC) ?: return
                        val json = intent.getStringExtra(PrefKeys.LOG_DUMP_EXTRA_LOGS) ?: "[]"
                        logs[proc] = parse(json)
                        intent.getStringExtra(PrefKeys.LOG_DUMP_EXTRA_STATUS)?.let {
                            if (it.isNotEmpty()) statuses[proc] = it
                        }
                        intent.getStringExtra(PrefKeys.LOG_DUMP_EXTRA_STATS)?.let {
                            if (it.isNotEmpty()) stats[proc] = it
                        }
                    }
                },
                IntentFilter(PrefKeys.LOG_DUMP_REPLY),
                Context.RECEIVER_EXPORTED
            )
        }.onFailure { HLog.w("LogCollector", "register failed: ${it.message}") }
    }

    /** 拉一轮：广播请求各 hook 进程 + 直读本进程缓冲（proc="app"）。 */
    fun request(context: Context) {
        mergeLocal()
        runCatching {
            context.applicationContext.sendBroadcast(Intent(PrefKeys.LOG_DUMP_REQUEST))
        }.onFailure { HLog.w("LogCollector", "request failed: ${it.message}") }
    }

    /** 模块自身缓冲并入 state（不经广播） */
    fun mergeLocal() {
        logs[HLog.proc()] = HLog.snapshot()
    }

    private fun parse(json: String): List<HLog.Entry> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HLog.Entry(
                wallMs = o.optLong("ts"),
                level = o.optString("lvl", "I").firstOrNull() ?: 'I',
                proc = o.optString("proc", "?"),
                tag = o.optString("tag", "?"),
                msg = o.optString("msg")
            )
        }
    }.getOrDefault(emptyList())

    /** 三进程全量快照合并（自检 v2/导出用），按进程分段、段内时间升序。 */
    fun mergedForExport(): Map<String, List<HLog.Entry>> = logs.toMap()

    /** IO 线程拉取并等待回传（自检 v2 用）：请求后短暂等待各进程 REPLY 落账。 */
    suspend fun requestAndAwait(context: Context, waitMs: Long = 1500L) {
        request(context)
        withContext(Dispatchers.IO) { Thread.sleep(waitMs) }
        mergeLocal()
    }
}
