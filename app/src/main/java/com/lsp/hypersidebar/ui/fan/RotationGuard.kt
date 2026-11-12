package com.lsp.hypersidebar.ui.fan

/**
 * 转屏窗口期的方向一致性判定（纯 JVM，2026-11-11 M25）。
 *
 * `rotation` 与 `Surface.ROTATION_*` 同值：0/2=竖屏（正/反），1/3=横屏（左/右），
 * -1=未知（调用方拿不到 display 时不判，交给窗口尺寸监听兜底）。
 *
 * 转屏动画期间 `Configuration.orientation` 可能已翻新、窗口却仍按旧方向布局（或反之），
 * 此时呼出扇形会按错坐标系画在屏幕中央。show 前用本判据丢弃这一次呼出。
 */
internal object RotationGuard {
    fun mismatch(rotation: Int, configLandscape: Boolean): Boolean =
        rotation >= 0 && configLandscape != (rotation == 1 || rotation == 3)
}
