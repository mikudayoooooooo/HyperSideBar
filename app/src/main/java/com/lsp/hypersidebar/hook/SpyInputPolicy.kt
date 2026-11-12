package com.lsp.hypersidebar.hook

/**
 * `SystemUiInputSpy` 的消费方门控（纯逻辑，无 Android 依赖 ⇒ 可 JUnit 覆盖）。
 *
 * 背景（2026-11-12，issue #12）：spy 原先在 SystemUI `Application.attach` 里**无条件**
 * 建全局输入 monitor；OS2/3 上 strip 流没有任何收端（`RelayGestureSink` 只在 `b2Enabled()`
 * 或底角兜底时放行），却仍按 ~60 次/秒 `sendBroadcast`。本对象把"要不要 monitor / 要不要
 * 转发某条流"的判据集中成三个纯函数，glue 只负责求值。
 *
 * 跨进程边界：SystemUI 进程拿不到 `:ui` 的 `b2Enabled()` 全量条件（`breaker`/
 * `DataDeadState` 是进程内状态），这里用可跨进程观测的代理 `moduleEnabled && os4`；
 * 精确的收端门（含兜底）仍由 `RelayGestureSink.dispatch` 拦。
 */
object SpyInputPolicy {

    /**
     * 是否需要一个 monitor。
     *
     * - OS4：strip 是唯一输入源（launcher 纯 native 无 Java hook）⇒ 恒需；
     * - 模块关：什么都不做；
     * - OS2/3：只有底角兜底（`cornerSwipe && fallbackOwned`）或空白点击需要。
     */
    fun needed(
        moduleEnabled: Boolean,
        os4: Boolean,
        cornerSwipe: Boolean,
        cornerFallbackOwned: Boolean,
        blankTapEnabled: Boolean,
    ): Boolean = moduleEnabled &&
        (os4 || (cornerSwipe && cornerFallbackOwned) || blankTapEnabled)

    /** strip 流（竖屏左右触发带）是否有消费方：只有 OS4 的 `:ui` 会消费。 */
    fun forwardStrip(moduleEnabled: Boolean, os4: Boolean): Boolean =
        moduleEnabled && os4

    /**
     * 角区流是否有消费方：OS4 的 `:ui`（`b2Enabled`）或 OS2/3 的底角兜底
     * （`:ui` 的 `cornerFallbackOwned`，要求 `cornerSwipe && fallback`）。
     */
    fun forwardCorner(
        moduleEnabled: Boolean,
        os4: Boolean,
        cornerSwipe: Boolean,
        fallbackOwned: Boolean,
    ): Boolean = moduleEnabled && cornerSwipe && (os4 || fallbackOwned)
}
