package com.lsp.hypersidebar.util

import android.content.Context
import com.lsp.hypersidebar.BuildConfig
import io.github.kyuubiran.ezxhelper.xposed.EzXposed

/**
 * 模块代码陈旧检测（2026-10-31，用户"更新 APK 后必须重启设备才恢复"的现场问题）。
 *
 * 已证实的 LSPosed 行为：`adb install -r` 换包后，宿主进程重启仍可能跑**旧 dex**
 * （模块 APK 路径/缓存未刷新），新代码独有的日志不出现——只有重启设备才生效。
 * 判据 = 运行中代码的 [BuildConfig.VERSION_CODE] vs 磁盘上模块 APK 的 versionCode。
 *
 * 用 `getPackageArchiveInfo(modulePath)` 而不是 `getPackageInfo(pkg)`：前者不受
 * Android 11 包可见性过滤，在任意宿主进程都读得到。
 */
object ModuleStaleness {

    private const val TAG = "ModuleStale"

    /** 每个进程只报一次（attach 期调用一次即可，不必逐次求值）。 */
    @Volatile private var logged = false

    /** 陈旧描述；一致/读不到模块路径 ⇒ null。 */
    fun diagnose(context: Context): String? {
        val path = runCatching { EzXposed.modulePath }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return null
        val installed = runCatching {
            context.packageManager.getPackageArchiveInfo(path, 0)?.longVersionCode
        }.getOrNull() ?: return null
        val running = BuildConfig.VERSION_CODE.toLong()
        if (installed == running) return null
        return "模块代码疑似陈旧：装包=$installed 运行=$running ⇒ LSPosed 未刷新 dex，重启设备后生效（APK=$path）"
    }

    /** 进程内首次调用时把结论写进 HLog（陈旧才写 W，正常静默）。 */
    fun logOnce(context: Context) {
        if (logged) return
        logged = true
        runCatching { diagnose(context) }.getOrNull()?.let { HLog.w(TAG, it) }
    }
}
