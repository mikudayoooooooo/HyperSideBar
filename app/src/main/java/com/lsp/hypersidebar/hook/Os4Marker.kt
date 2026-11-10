package com.lsp.hypersidebar.hook

/**
 * OS4 侧边栏在场标记（跨 hook 共享的只读事实，2026-10-05）：
 *
 * TurboLayout 在锚点解析时测定（`com.miui.sidebar.ISidebarOverlay` AIDL 稳定名存在性，
 * 与混淆漂移无关）。消费方：
 * - FreeformRelayHook 探针应答附带 os4=true → 模块 App 状态卡据此把"触发端（桌面）
 *   无应答"判为 OS4 预期形态（桌面纯 native 无 Java hook，U2 定案）而非通道异常；
 * - 后续任何需要"当前宿主是否 OS4 侧边栏形态"的判定都从这里读，勿再各自探测。
 */
object Os4Marker {
    /** AIDL 稳定接口名（未混淆，与混淆漂移无关）——全仓唯一字面量，探测处统一引用。 */
    const val SIDEBAR_ITF = "com.miui.sidebar.ISidebarOverlay"

    @Volatile
    var sidebarPresent: Boolean = false

    /**
     * 模块进程（设置页等）用的**同步** ROM 判据：`Build.VERSION.INCREMENTAL` 形如
     * `OS4.0.6.0.XPCCNXM`（与本机 `ro.mi.os.version.name=OS4.0` 同一来源的展示值，
     * 2026-11-10 真机 os4-8602 实测）。
     *
     * 为什么不用 [sidebarPresent]/AIDL 探测：`ISidebarOverlay` 按 dex 内容全量扫描
     * （2026-11-10 真机）**只定义在 MIUISecurityCenter.apk 的 classes3.dex**，
     * boot jar / MiuiSystemUI.apk / MiuiHome.apk 均无 ⇒ 模块 App 进程（以及 SystemUI
     * 进程）都解析不到它，设置页需要**同步**判据只能走 ROM 版本前缀。
     * 只匹配 `OS4.`：OS1/2/3（预计 INCREMENTAL 为 `OS3.`/`OS2.` 前缀【待验证】，与
     * ro.mi.os.version.name 同族命名）及任何未知形态一律 false ⇒ 与历史行为一致，不做额外隐藏。
     */
    fun romIsOs4(): Boolean = android.os.Build.VERSION.INCREMENTAL.startsWith("OS4.")
}
