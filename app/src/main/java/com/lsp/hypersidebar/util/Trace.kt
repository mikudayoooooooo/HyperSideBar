package com.lsp.hypersidebar.util

import android.os.SystemClock

/**
 * 呼出链路追踪 id（2026-09-05）：单次"呼出→选中→转发→执行"全程携带。
 *
 * - home 进程在 showInternal 生成并写入 [current]；
 * - 跨进程经 intent extra [EXTRA] 随 relay/代发广播传递，:ui 与模块进程收到时
 *   覆写**自己进程**的 current（object 随类加载器 per-process，互不串扰）；
 * - 各环节日志行以 "[id]" 前缀携带——logcat 按该 id grep 即得单次全链路三段拼接。
 */
object Trace {
    /** intent extra 键（fan relay / root 代发 / 结果回告共用） */
    const val EXTRA = "trace"

    /** 本进程当前链路 id */
    @Volatile var current: String? = null

    fun new(): String = "t" + java.lang.Long.toString(SystemClock.elapsedRealtime(), 36)
}
