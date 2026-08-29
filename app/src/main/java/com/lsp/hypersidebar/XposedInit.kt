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
                // 小白条通道宿主 + B 链路执行端（EDGE 模式下小白条隐藏穿透但进程保活）
                if (turboLayoutHook == null) {
                    turboLayoutHook = TurboLayout(getRemotePreferences("hyperSidebar"))
                }
                if (freeformRelayHook == null) {
                    freeformRelayHook = FreeformRelayHook()
                }
                initHooks(turboLayoutHook!!, freeformRelayHook!!)
            }
            param.packageName == "com.miui.home" && procName == "com.miui.home" -> {
                // 边缘手势通道（channelMode=EDGE 时激活，快速滑动零干扰透传）
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
