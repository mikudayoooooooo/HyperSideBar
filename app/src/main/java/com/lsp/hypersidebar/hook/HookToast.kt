package com.lsp.hypersidebar.hook

import android.content.Context
import io.github.kyuubiran.ezxhelper.xposed.EzXposed

/**
 * EzXposed.appContext 的安全取值（未就绪时抛 NPE 而非返回 null，实测教训）。
 * toast 统一入口已收口至 util/ToastMain.toastOnMain（0912）。
 */
internal fun safeAppContext(): Context? = runCatching { EzXposed.appContext }.getOrNull()
