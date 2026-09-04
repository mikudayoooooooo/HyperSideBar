package com.lsp.hypersidebar.util

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程级 remotePrefs 绑定桥（迭代五 D7 修复）。
 *
 * 根因（logcat 实证 2026-09-04）：XposedServiceHelper 进程内全局只绑定一次——首个
 * 注册方触发 bind，后续注册方（第二个 Activity 实例）不再收到 onServiceBind 回调。
 * 此前 MainActivity 与 AllAppsActivity 各自注册 listener 且绑定结果互不共享：
 * "先开设置页（完成绑定）→ 再呼出 AllApps（二次注册无回调、静态缓存空）"路径下，
 * AllApps 永远落在本地空壳 prefs 上——固定应用消失（D7）、设置页计数 0（D1）同根。
 *
 * 修复：全进程只注册一次 listener，绑定结果（RemotePreferences）存本单例；
 * [addListener] 幂等——已绑定时对新 listener 立即同步回调。
 */
object RemotePrefsBridge : XposedServiceHelper.OnServiceListener {

    private const val TAG = "RemotePrefsBridge"

    const val PREFS_NAME = "hyperSidebar"

    /**
     * LSPosed 服务端 RemotePreferences 快照；onServiceDied 后为 null。
     * 用 Compose state 承载：绑定完成本身即全局重组信号——任何在组合期读取
     * 本值的页面（如 SettingsPage 的 D1 修复）都会被强制刷新，不依赖上游
     * 参数链（navigation3 entry 可能固化旧参数捕获）。
     */
    var prefs: SharedPreferences? by mutableStateOf(null)
        private set

    /** 服务句柄（MainScreen 等需要直接调 service API 的消费者使用）。 */
    @Volatile
    var service: XposedService? = null
        private set

    private val listeners = CopyOnWriteArrayList<(SharedPreferences) -> Unit>()
    private var registered = false

    /**
     * 幂等注册：进程内只触发一次 [XposedServiceHelper.registerListener]；
     * 若绑定已完成，对新 listener 立即同步回调（回调线程=本函数调用线程，
     * 消费者自行切主线程更新 UI state）。
     */
    @Synchronized
    fun addListener(onBound: (SharedPreferences) -> Unit) {
        listeners.add(onBound)
        if (!registered) {
            registered = true
            XposedServiceHelper.registerListener(this)
        }
        prefs?.let(onBound)
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        Log.i(TAG, "onServiceBind")
        // getRemotePreferences 是一次性同步 binder 拉取全量快照，挪出回调线程
        Thread {
            runCatching { service.getRemotePreferences(PREFS_NAME) }.onSuccess { p ->
                prefs = p
                // 令牌同步（模块进程唯一可写端，幂等）：跨进程广播防伪依赖此值
                RelayToken.sync(p)
                Log.i(TAG, "remotePrefs bound, customApps=" +
                    runCatching { p.getStringSet(com.lsp.hypersidebar.prefs.PrefKeys.CUSTOM_APPS, emptySet()) }
                        .getOrNull()?.size)
                listeners.forEach { runCatching { it(p) } }
            }
        }.start()
    }

    override fun onServiceDied(service: XposedService) {
        Log.w(TAG, "onServiceDied")
        this.service = null
        prefs = null
    }
}
