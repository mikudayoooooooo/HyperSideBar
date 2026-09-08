package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lsp.hypersidebar.ShortcutRelayReceiver
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.FanLaunchStrategy
import com.lsp.hypersidebar.util.FailureReason
import com.lsp.hypersidebar.util.FanPrewarmer
import com.lsp.hypersidebar.util.FreeformLauncher
import com.lsp.hypersidebar.util.LaunchResult
import com.lsp.hypersidebar.util.QsTileClickBridge
import com.lsp.hypersidebar.util.UnfreezeBridge
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.Trace
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutLauncher
import com.lsp.hypersidebar.util.SystemLaunchStrategy

private const val TAG = "FanLaunch"

/**
 * securitycenter:ui 进程的直调策略。
 * 打开原生面板时经 PanelHideState 短暂隐藏 dock（hookDockLayoutVisibility 消费），5s 后恢复。
 */
class DirectLaunchStrategy(
    /** :ui 的 remotePrefs（只读）：取模块下发的跨进程防伪令牌 */
    private val remotePrefs: SharedPreferences
) : FanLaunchStrategy {

    override fun launchFreeform(context: Context, pkg: String) {
        FreeformLauncher.launch(context, pkg)
    }

    override fun launchAllApps(context: Context) {
        // 面板数据在启动时经 intent 传递：:ui 平台签名特权不受 hidden API 限制，getFreeformSuggestionList
        // 反射可用；模块进程是普通 App，被 hidden API blocklist 拒绝（实测 denied），
        // 面板进程内的 DataLoader 永远拿不到建议列表
        val list = ArrayList(com.lsp.hypersidebar.util.DataLoader.loadApps(context))
        FreeformLauncher.launchSelfFreeform(
            context,
            com.lsp.hypersidebar.ui.allapps.AllAppsActivity::class.java
        ) { intent ->
            intent.putStringArrayListExtra(
                com.lsp.hypersidebar.ui.allapps.AllAppsActivity.EXTRA_SUGGESTIONS, list
            )
        }
    }

    override fun openNativePanel(context: Context) {
        PanelHideState.hidden.set(true)
        val intent = Intent("com.miui.gamebooster.PANNEL_OPEN").apply {
            setPackage("com.miui.securitycenter")
        }
        context.sendBroadcast(intent, "com.miui.gamebooster.permission.PANNEL_OPEN")
        Log.i(TAG, "openNativePanel: broadcast sent")
        Handler(Looper.getMainLooper()).postDelayed({
            PanelHideState.hidden.set(false)
        }, 5000)
    }

    override fun launchShortcut(context: Context, shortcut: ShortcutAction) {
        // QS_TILE 首选 SystemUI hook 直点（数据层 QSTile.click，无面板状态门禁、
        // 零可见动作；2026-09-05 源码定位+真机测试矩阵定案）。hook 不在（未加作用域/
        // ROM 漂移）或磁贴不在当前 QS → 回退模块 App root 代发（expand-settings 三连，
        // QS 闪现，仅兜底）。后台线程执行避免阻塞接收器主线程。
        if (shortcut.kind == ShortcutKind.QS_TILE) {
            val pkg = shortcut.packageName ?: ""
            val cls = shortcut.serviceName ?: ""
            if (pkg.isNotEmpty() && cls.isNotEmpty()) {
                val full = if (cls.startsWith(".")) "$pkg$cls" else cls
                val appCtx = context.applicationContext
                Thread {
                    // 冻结防线（2026-09-07 方案 2 bind 唤醒 relay）：bind 模块 App 的
                    // UnfreezeRelayService——bind 本身唤醒冻结/已死的模块进程（uid 1000
                    // 服务调用 → SmartPower THAW / 冷启动），连上后模块 su 解冻磁贴宿主，
                    // 回执后 :ui 再发点击。解冻失败（无 su/超时）不阻塞点击——目标未必
                    // 冻结，此步只是防线；3s 节流内跳过（tobg 重冻以秒计，relay 常开换确定性）。
                    // 退役记录：广播 relay（冻结进程收不到广播，靠白名单续命）→
                    // kill 预热（误伤目标进程状态）→ 现方案（su 直写 freeze=0，状态零损失）
                    UnfreezeBridge.unfreezeBlocking(
                        appCtx, pkg, RelayToken.read(remotePrefs), Trace.current
                    )
                    val viaHook = QsTileClickBridge.sendBlocking(
                        appCtx, "$pkg/$full", RelayToken.read(remotePrefs)
                    )
                    if (!viaHook) {
                        relayLaunchToModule(appCtx, shortcut)
                    }
                    Handler(Looper.getMainLooper()).post {
                        runCatching {
                            val msg = if (viaHook) "已触发磁贴：${shortcut.label}"
                            else "已发送磁贴指令：${shortcut.label}"
                            Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
                return
            }
        }

        // SHORTCUT_ID（动态/固定快捷方式，2026-09-08）：目标 intent 对非桌面不可见，
        // validate 无从谈起；本进程无桌面角色——launcher 进程桥 startShortcut 代发
        if (shortcut.kind == ShortcutKind.SHORTCUT_ID) {
            val appCtx0 = context.applicationContext
            Thread {
                val result = ShortcutLauncher.launchShortcutId(
                    appCtx0, shortcut, RelayToken.read(remotePrefs)
                )
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        Toast.makeText(
                            appCtx0,
                            if (result is LaunchResult.Success) "已启动：${shortcut.label}"
                            else "无法启动：${shortcut.label}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.start()
            return
        }

        // 非 exported 目标预检失败时直接转发模块 App 代发（§2.4 实测定案）：
        // 本进程（:ui，平台签名特权；注意并非 uid 1000）startActivityAsUser 对启动不了的目标静默假成功
        // （不抛异常、实际不启动，无法靠异常触发 root 回退），且本进程无 su 授权；
        // 模块 App 进程持 root，其 validate→直试→ANF→su 链路已被编辑页测试验证。
        if (shortcut.kind != ShortcutKind.SERVICE && shortcut.kind != ShortcutKind.SHORTCUT_ID) {
            val check = ShortcutLauncher.validate(context, shortcut)
            if (check is LaunchResult.Failure &&
                (check.reason == FailureReason.ACTIVITY_NOT_FOUND ||
                    check.reason == FailureReason.NOT_EXPORTED)
            ) {
                if (relayLaunchToModule(context, shortcut)) return
                Log.w(TAG, "relay launch to module app failed, falling back to local launch")
            }
        }

        val result = ShortcutLauncher.launch(context, shortcut, SystemLaunchStrategy())
        // service 拉起无界面反馈，toast 显式提醒（PRD §7.3.2）
        if (shortcut.kind == ShortcutKind.SERVICE && result is LaunchResult.Success) {
            runCatching {
                Toast.makeText(context, "已拉起服务：${shortcut.label}", Toast.LENGTH_SHORT).show()
            }
        } else if (result is LaunchResult.Failure) {
            // PRD §9.4：非编辑页场景启动失败 toast 兜底（编辑页测试启动自带原因展示）
            runCatching {
                Toast.makeText(context, "activity/Service无法正常启动", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** fan 呼出预热（2026-09-07 预热制，替代 unfreeze relay 的免 root 方案）：
     *  ① QS_TILE 目标包：呼出后 ~150ms kill 冻结进程 + 方案 B 预 bind（只 prime 不点击），
     *     AMS 拉全新进程（不在 frozen cgroup），点击时 TileService 已连接直连零延迟；
     *  ② 图标缓存预灌（扇形固定应用 + AllApps 首屏），面板打开无图标闪现。 */
    override fun onFanShown(context: Context, quickActions: List<ShortcutAction>, fanAppPkgs: List<String>) {
        // 预 bind 走 SystemUI hook，cn 必须是扁平组件名（unflattenFromString 对裸包名
        // 返回 null → 接收端早退，预热静默空转）——与点击路径 sendBlocking 的 "$pkg/$full" 同构
        val tileTargets = quickActions
            .filter {
                it.kind == ShortcutKind.QS_TILE &&
                    !it.packageName.isNullOrEmpty() && !it.serviceName.isNullOrEmpty()
            }
            .map { sa ->
                val pkg = sa.packageName!!
                val cls = sa.serviceName!!
                val full = if (cls.startsWith(".")) "$pkg$cls" else cls
                pkg to "$pkg/$full"
            }
            .distinctBy { it.second }
        FanPrewarmer.onFanShown(context, tileTargets, fanAppPkgs, RelayToken.read(remotePrefs))
    }

    /** :ui → 模块 App root 代发：完整 ShortcutAction JSON 随广播携带（接收端无需读 prefs）。
     *  显式组件寻址（接收端无 intent-filter，action 寻址匹配不上会被静默丢弃）。 */
    private fun relayLaunchToModule(context: Context, shortcut: ShortcutAction): Boolean {
        return runCatching {
            val intent = Intent(PrefKeys.RELAY_LAUNCH_ACTION).apply {
                // 包名=applicationId（迭代五已迁 io.github.mikudayoooooooo.hypersidebar），
                // 类路径=源码 namespace（未随 applicationId 迁移），两者分别取常量/类引用，
                // 杜绝再出现拼死字符串导致的寻址漂移
                setClassName(
                    FreeformLauncher.MODULE_PACKAGE,
                    ShortcutRelayReceiver::class.java.name
                )
                putExtra(
                    PrefKeys.RELAY_LAUNCH_EXTRA_SHORTCUT,
                    shortcut.toJson().toString()
                )
                putExtra(com.lsp.hypersidebar.util.Trace.EXTRA, com.lsp.hypersidebar.util.Trace.current)
                RelayToken.attach(this, RelayToken.read(remotePrefs))
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "[${Trace.current ?: "-"}] relay launch to module app sent: id=${shortcut.id} kind=${shortcut.kind}")
            true
        }.getOrElse {
            Log.e(TAG, "relay launch to module app failed", it)
            false
        }
    }
}
