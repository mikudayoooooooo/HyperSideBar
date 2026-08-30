package com.lsp.hypersidebar

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
                if (turboLayoutHook == null) {
                    turboLayoutHook = TurboLayout(getRemotePreferences("hyperSidebar"))
                }
                if (freeformRelayHook == null) {
                    freeformRelayHook = FreeformRelayHook()
                }
                initHooks(turboLayoutHook!!, freeformRelayHook!!)
            }
            param.packageName == "com.miui.home" && procName == "com.miui.home" -> {
                // 竖屏边缘手势通道（内滑+停顿零干扰透传；横屏触发已移交 :ui B 路线）
                if (edgeGestureHook == null) {
                    edgeGestureHook = EdgeGestureHook(getRemotePreferences("hyperSidebar"))
                }
                initHooks(edgeGestureHook!!)
            }
            else -> Log.d(TAG, "Skip package/process: ${param.packageName} / $procName")
        }
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
