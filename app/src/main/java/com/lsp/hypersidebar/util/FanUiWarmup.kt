package com.lsp.hypersidebar.util

import android.util.Log

/**
 * 扇形 UI 类族预载（呼出首装配卡顿缓解，2026-09-05 日志实锤）：
 * ART 对 ComposeFanHost.FanMenuWithTheme 的字节码校验在 launcher 主线程耗时 362ms
 * （首装配 cost=471ms 的主构成）——"有时候呼出会卡"的实凶，只发生在进程刚重启后的
 * 首呼出且系统繁忙时（系统空闲时同一路径实测仅 39ms）。类校验发生在类加载线程，
 * 而扇形 UI 类族首呼出时才首次加载——本预热把它们挪到 hook init 的后台线程加载，
 * 校验成本从用户可感的首呼出转移到进程启动（无感）。若校验实为"首次调用时软失败
 * 重校"则预热无效（无副作用，以首装配 cost 是否回落为准）。
 */
object FanUiWarmup {

    private const val TAG = "FanUiWarmup"

    /** 文件类名（Kotlin top-level → *Kt）猜错只打 miss 日志，无害；主类首载后传递加载大半依赖 */
    private val CLASSES = listOf(
        "com.lsp.hypersidebar.ui.fan.ComposeFanHost",
        "com.lsp.hypersidebar.ui.fan.FanMenuComposeKt",
        "com.lsp.hypersidebar.ui.fan.FanGeometryKt",
        "com.lsp.hypersidebar.ui.fan.QuickAppsBarKt",
        "com.lsp.hypersidebar.ui.fan.IconLoaderKt",
        "com.lsp.hypersidebar.ui.fan.FanAppInfo",
        "com.lsp.hypersidebar.ui.fan.FanConfig",
        "com.lsp.hypersidebar.ui.fan.FanThemeColors",
        "com.lsp.hypersidebar.theme.ThemeKt"
    )

    @Volatile private var warmed = false

    fun warm() {
        if (warmed) return
        warmed = true
        Thread {
            val t0 = android.os.SystemClock.elapsedRealtime()
            CLASSES.forEach { name ->
                runCatching { Class.forName(name, false, FanUiWarmup::class.java.classLoader) }
                    .onFailure { Log.w(TAG, "preload miss: $name (${it.message})") }
            }
            Log.i(TAG, "fan UI classes preloaded in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        }.apply {
            isDaemon = true
            name = "FanUiWarmup"
        }.start()
    }
}
