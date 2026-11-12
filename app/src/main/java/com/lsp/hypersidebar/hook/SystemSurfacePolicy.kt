package com.lsp.hypersidebar.hook

/**
 * 「系统表面是否在最上层」闸（纯逻辑，无 Android 依赖 ⇒ 可 JUnit 覆盖）。
 *
 * 背景（2026-11-12，用户实测）：有小窗在屏且「点击空白处」接管开启时，下拉 QS/通知栏后
 * 点磁贴，spy 会把这次系统窗口上的点击当"小窗之外的空白"——`isBlank` 只认小窗几何/IME，
 * 不认"系统表面在上" ⇒ `takeoverArmed` → pilfer，磁贴点击被抢流。
 *
 * 数据源是 SystemUI 的 `NotificationShadeWindowState`（`mCurrentState`，Dagger provider
 * 懒解析，见 `FreeformBlankTapHook.systemSurfaceOnTop`）。本对象只表达判定语义：
 * 任一"系统表面在上"的标志为真 ⇒ 不接管、不动作（fail-closed）。
 *
 * `keyguardShowing && keyguardOccluded` 不算（应用覆盖在锁屏之上，用户实际在操作应用，
 * 例如相机/电话）；`bouncer` 显示时仍拦。
 */
object SystemSurfacePolicy {

    fun blocksBlankTap(
        controlCenterExpanded: Boolean,
        panelVisible: Boolean,
        qsExpanded: Boolean,
        shadeOrQsExpanded: Boolean,
        keyguardShowing: Boolean,
        keyguardOccluded: Boolean,
        bouncerShowing: Boolean,
    ): Boolean =
        controlCenterExpanded || panelVisible || qsExpanded || shadeOrQsExpanded ||
            (keyguardShowing && !keyguardOccluded) || bouncerShowing
}
