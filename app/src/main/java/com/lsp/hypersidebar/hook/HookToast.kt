package com.lsp.hypersidebar.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.kyuubiran.ezxhelper.xposed.EzXposed

private val toastMainHandler = Handler(Looper.getMainLooper())

/**
 * hook 侧 toast 统一入口（1C）：投递主线程 + 全程容错，:ui / launcher 通用。
 * EzXposed.appContext 未就绪时（launcher init 期会直接抛 NPE）传 null 静默跳过。
 */
internal fun toastOnMain(context: Context?, msg: String) {
    if (context == null) return
    toastMainHandler.post {
        runCatching {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }.onFailure { Log.w("HookToast", "toast failed: ${it.message}") }
    }
}

/** EzXposed.appContext 的安全取值（未就绪时抛 NPE 而非返回 null，实测教训）。 */
internal fun safeAppContext(): Context? = runCatching { EzXposed.appContext }.getOrNull()
