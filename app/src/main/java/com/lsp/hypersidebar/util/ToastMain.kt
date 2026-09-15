package com.lsp.hypersidebar.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

private val toastMainHandler = Handler(Looper.getMainLooper())

/**
 * 全进程通用 toast 统一入口（0912 收口：此前 hook/HookToast、FreeformLauncher 私有副本、
 * DataLoader 内联三处各持一份实现，时长/容错互不一致）：投递主线程 + 全程容错。
 * context 为 null 静默跳过（hook 进程 appContext 未就绪场景）。
 */
fun toastOnMain(context: Context?, msg: String) {
    if (context == null) return
    toastMainHandler.post {
        runCatching {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }.onFailure { HLog.w("ToastMain", "toast failed: ${it.message}") }
    }
}
