package com.lsp.hypersidebar.hook

import com.lsp.hypersidebar.prefs.PrefKeys

/**
 * hook 进程内状态探针（§2.5.4）：设置页打开时发有序 ping，接收器应答时经本对象
 * 读取本进程 hook 的实时状态并回 resultCode（[PrefKeys.PROBE_CODE_*]）。
 *
 * provider 由各宿主 hook 在 init 时注入，闭包直读各自的内存态（breaker/passthroughDegraded），
 * 应答时取值——不镜像不同步。未注入（宿主 hook 未初始化）按无应答处理。
 */
internal object HookProbeState {

    /** :ui 进程注入（TurboLayout：熔断/降级双态） */
    @Volatile var uiProvider: (() -> Int)? = null

    /** home 进程注入（EdgeGestureHook：熔断单态） */
    @Volatile var homeProvider: (() -> Int)? = null

    fun uiCode(): Int = uiProvider?.invoke() ?: PrefKeys.PROBE_CODE_DEAD

    fun homeCode(): Int = homeProvider?.invoke() ?: PrefKeys.PROBE_CODE_DEAD
}
