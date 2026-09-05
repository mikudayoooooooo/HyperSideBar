package com.lsp.hypersidebar

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import com.lsp.hypersidebar.hook.BaseHook
import com.lsp.hypersidebar.hook.EdgeGestureHook
import com.lsp.hypersidebar.hook.FreeformRelayHook
import com.lsp.hypersidebar.hook.TurboLayout

class XposedInit : XposedModule() {

    private val TAG = "XposedInit"
    private var turboLayoutHook: TurboLayout? = null
    private var freeformRelayHook: FreeformRelayHook? = null
    private var edgeGestureHook: EdgeGestureHook? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        EzXposed.initOnPackageReady(param)

        val procName = currentProcessName()
        when {
            param.packageName == "com.miui.securitycenter" && procName.endsWith(":ui") -> {
                // 横屏 B 路线触发端 + 竖屏小白条隐藏穿透宿主 + 执行端（fan 选中动作本进程直执行）
                // SyncedPrefs 包装（同 home 端批次 2）：总开关/横屏 dwell 等读取走同步广播
                // 缓存命中，实时性不再单靠 LSPosed push 订阅
                val prefs = com.lsp.hypersidebar.util.SyncedPrefs(remotePrefsWithProbe())
                if (turboLayoutHook == null) {
                    turboLayoutHook = TurboLayout(prefs)
                }
                if (freeformRelayHook == null) {
                    // prefs 同时供 FreeformRelay 做跨进程广播令牌校验（批次 0 安全修复）
                    freeformRelayHook = FreeformRelayHook(prefs)
                }
                initHooks(turboLayoutHook!!, freeformRelayHook!!)
            }
            param.packageName == "com.miui.home" && procName == "com.miui.home" -> {
                // 竖屏边缘手势通道（内滑+停顿零干扰透传；横屏触发已移交 :ui B 路线）
                if (edgeGestureHook == null) {
                    // SyncedPrefs 包装（批次 2）：配置同步广播缓存命中优先——
                    // RemotePreferences 本体是死快照且 LSPosed 框架侧还做进程级
                    // 缓存（重调 API 也是同一实例），配置实时性只能走广播通道
                    edgeGestureHook = EdgeGestureHook(
                        com.lsp.hypersidebar.util.SyncedPrefs(remotePrefsWithProbe())
                    )
                }
                initHooks(edgeGestureHook!!)
            }
            else -> Log.d(TAG, "Skip package/process: ${param.packageName} / $procName")
        }
    }

    /**
     * remote prefs 推送订阅（P5 实测定案：**此监听是承重件，勿删**）。
     * LSPosed 对 hook 进程的 remote prefs 变更推送是订阅触发的——不注册监听时
     * getRemotePreferences 返回的是启动时死快照，设置 App 的滑条改动永远不可见
     * （2026-08-30 实测：注册后 push 全量到达、下一呼出即读到新值）。
     * 副作用仅一条日志（键名），留作同步通道的存活遥测。
     */
    private fun remotePrefsWithProbe(): SharedPreferences {
        val prefs = getRemotePreferences("hyperSidebar")
        runCatching {
            prefs.registerOnSharedPreferenceChangeListener { _, key ->
                Log.d(TAG, "remote prefs push: $key")
            }
        }.onFailure { Log.w(TAG, "remote prefs listener register failed: ${it.message}") }
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
