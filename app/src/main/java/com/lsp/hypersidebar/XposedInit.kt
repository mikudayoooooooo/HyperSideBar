package com.lsp.hypersidebar

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import com.lsp.hypersidebar.hook.BaseHook
import com.lsp.hypersidebar.hook.TurboLayout

class XposedInit : XposedModule() {

    private val TAG = "XposedInit"
    private var turboLayoutHook: TurboLayout? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "com.miui.securitycenter") return
        EzXposed.initOnPackageReady(param)

        val procName = currentProcessName()
        if (!procName.endsWith(":ui")) {
            Log.d(TAG, "Skip non-:ui process: $procName")
            return
        }

        if (turboLayoutHook == null) {
            turboLayoutHook = TurboLayout(getRemotePreferences("hyperSidebar"))
        }
        initHooks(turboLayoutHook!!)
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
