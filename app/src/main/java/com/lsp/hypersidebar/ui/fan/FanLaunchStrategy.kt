package com.lsp.hypersidebar.ui.fan

import android.content.Context
import com.lsp.hypersidebar.util.ShortcutAction

/** launcher → securitycenter:ui 的执行转发广播 action（FreeformRelayHook 注册接收）。 */
const val ACTION_FAN_LAUNCH = "com.lsp.hypersidebar.ACTION_FAN_LAUNCH"

/**
 * 圈内末位"全部应用"入口的哨兵包名（PRD §7.3.2：扇形面板最后一位常驻展示全部应用面板）。
 * 作为 FanAppInfo.packageName 流经渲染/选中，到达执行层后被分支为 launchAllApps，
 * 永远不会被当作真实包名启动。
 */
const val ALL_APPS_PKG = "__all_apps__"

/**
 * fan 选中项的执行策略：随宿主进程而异。
 * - DirectLaunchStrategy（securitycenter:ui）：直接调用本进程能力（FreeformLauncher / ShortcutLauncher+System）
 * - BroadcastLaunchStrategy（com.miui.home）：广播到 :ui 执行（FreeformUtil/MiuiMultiWindow 仅在 :ui 验证可用）
 */
interface FanLaunchStrategy {
    fun launchFreeform(context: Context, pkg: String)
    fun launchAllApps(context: Context)
    fun openNativePanel(context: Context)
    fun launchShortcut(context: Context, shortcut: ShortcutAction)
}
