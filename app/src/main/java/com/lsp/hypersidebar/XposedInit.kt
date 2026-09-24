package com.lsp.hypersidebar

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.hook.BaseHook
import com.lsp.hypersidebar.hook.EdgeGestureHook
import com.lsp.hypersidebar.hook.FreeformRelayHook
import com.lsp.hypersidebar.hook.SystemUiHook
import com.lsp.hypersidebar.hook.TurboLayout
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefsFiles

class XposedInit : XposedModule() {

    private val TAG = "XposedInit"
    private var turboLayoutHook: TurboLayout? = null
    private var freeformRelayHook: FreeformRelayHook? = null
    private var edgeGestureHook: EdgeGestureHook? = null
    private var systemUiHook: SystemUiHook? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        // NativeLibLoader 抽 libdexkit.so 需要模块 APK 路径；必须在任何宿主 init 之前就位。
        runCatching { EzXposed.initModuleResources() }
            .onFailure { Log.w(TAG, "initModuleResources failed: ${it.message}") }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        EzXposed.initOnPackageReady(param)

        val procName = currentProcessName()
        when {
            param.packageName == HostPackages.UI_HOST && procName.endsWith(HostPackages.UI_PROCESS_SUFFIX) -> {
                // 横屏 B 路线触发端 + 竖屏小白条隐藏穿透宿主 + 执行端（fan 选中动作本进程直执行）
                // SyncedPrefs 包装（同 home 端批次 2）：总开关/横屏 dwell 等读取走同步广播
                // 缓存命中，实时性不再单靠 LSPosed push 订阅
                val prefs = com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                if (turboLayoutHook == null) {
                    turboLayoutHook = TurboLayout(prefs)
                }
                if (freeformRelayHook == null) {
                    // prefs 同时供 FreeformRelay 做跨进程广播令牌校验（批次 0 安全修复）
                    freeformRelayHook = FreeformRelayHook(prefs)
                }
                initHooks(turboLayoutHook!!, freeformRelayHook!!)
            }
            param.packageName == HostPackages.HOME && procName == HostPackages.HOME -> {
                // 竖屏边缘手势通道（内滑+停顿零干扰透传；横屏触发已移交 :ui B 路线）
                if (edgeGestureHook == null) {
                    // SyncedPrefs 包装（批次 2）：配置同步广播缓存命中优先——
                    // RemotePreferences 本体是死快照且 LSPosed 框架侧还做进程级
                    // 缓存（重调 API 也是同一实例），配置实时性只能走广播通道
                    edgeGestureHook = EdgeGestureHook(
                        com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                    )
                }
                initHooks(edgeGestureHook!!)
            }
            // 仅主进程：SystemUI 子进程（截图等）若也注册接收器，有序广播可能被
            // 子进程抢答 resultCode=0 覆盖主进程的点击结果
            param.packageName == HostPackages.SYSTEM_UI && procName == HostPackages.SYSTEM_UI -> {
                // 批次 3：QS 磁贴数据层直点桥（click-tile 门禁在回调层，QSTile.click 无约束）
                if (systemUiHook == null) {
                    systemUiHook = SystemUiHook(
                        com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs())
                    )
                }
                initHooks(systemUiHook!!)
            }
            else -> Log.d(TAG, "Skip package/process: ${param.packageName} / $procName")
        }
    }

    /**
     * hook 进程侧 remote prefs 获取（0915 读 libxposed-service 源码纠正了此前的错误认知）。
     *
     * **事实**：读侧的 `RemotePreferences` 永远是 `newInstance` 那一刻的快照——它内部只有一份
     * 本地 `mMap`（`getXxx` 全读它），而 `IXposedService` 的 AIDL 里**没有任何 prefs 变更
     * 推送接口**；`registerOnSharedPreferenceChangeListener` 只对**本进程自己的写入**回调
     * （`Editor.doUpdate()`），hook 进程从不写这些键，且它内部用 `WeakHashMap` 存 listener。
     *
     * 所以此前那条「注册监听是承重件、注册后 push 全量到达、下一呼出即读到新值」的注释与
     * 对应实现都是错的（那个监听既不会被触发、还会被 GC 回收），已删除。
     * 配置实时性完全走 `ConfigSync` 广播 / `ConfigPullBridge` 拉取通道，
     * 读取分流由 `SyncedPrefs` 装饰器负责。
     */
    private fun hookSidePrefs(): SharedPreferences {
        val prefs = getRemotePreferences(PrefsFiles.REMOTE)
        // 高频明细日志开关（§11.2）：init 读一次，此后随 ConfigSync.applySync 刷新
        HLog.verboseEnabled = runCatching {
            prefs.getBoolean(com.lsp.hypersidebar.prefs.PrefKeys.DEBUG_VERBOSE_LOGS, false)
        }.getOrDefault(false)
        return prefs
    }

    private fun currentProcessName(): String {
        return try {
            val clz = Class.forName("android.app.ActivityThread")
            val method = clz.getMethod("currentProcessName")
            method.invoke(null) as? String ?: ""
        } catch (_: Exception) { "" }
    }

    private fun initHooks(vararg hooks: BaseHook) {
        for (h in hooks) {
            if (h.isInit) continue
            try {
                h.init()
                h.isInit = true
                Log.i(TAG, "Hook [${h.name}] registered OK")
            } catch (e: Exception) {
                Log.e(TAG, "Hook [${h.name}] FAILED: ${e.message}", e)
            }
        }
    }
}
