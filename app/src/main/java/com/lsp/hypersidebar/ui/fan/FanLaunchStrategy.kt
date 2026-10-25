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
    /**
     * 小窗启动。@param source 呼出扇形时冻结的前台应用（C3/C4 穿线，null=未知/
     * launcher/桌面——该次不记上下文）；launcher 侧经 FAN_EXTRA_SOURCE 广播随行。
     */
    fun launchFreeform(context: Context, pkg: String, source: String? = null)
    /** 面板自身小窗。@param source 三跳透传（fan→面板 intent→面板回传广播→记录点）。 */
    fun launchAllApps(context: Context, source: String? = null)
    fun openNativePanel(context: Context)
    fun launchShortcut(context: Context, shortcut: ShortcutAction)

    /**
     * fan 呼出后的预热钩子（2026-09-07 预热制）：show 成功后调用，主线程、立即返回。
     * 默认空实现（launcher 侧无预热语义）；仅 :ui 的 DirectLaunchStrategy 覆写——
     * QS_TILE 目标包 kill+预 bind（FanPrewarmer）、图标缓存预灌（AppIconCache）。
     */
    fun onFanShown(context: Context, quickActions: List<ShortcutAction>, fanAppPkgs: List<String>) {}
}
