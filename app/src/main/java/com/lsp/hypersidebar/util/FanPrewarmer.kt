package com.lsp.hypersidebar.util

import android.content.Context
import android.util.Log

/**
 * fan 呼出预热器（2026-09-07 方案 2：bind 唤醒 relay + prebind，无 kill）。
 *
 * **两层冻结必须分清**（勿再混淆）：
 * - 目标 App（磁贴宿主，如 quickpay/clash）被冻结 → 发往它的 oneway onClick 积压不
 *   执行 = 磁贴点不动的**直接原因**；本类处理的就是这一层。
 * - 模块 App 被冻结 → 收不到 :ui 的广播（旧 broadcast relay 的死因）→ 只破坏
 *   **relay 自身**。现 relay 走 bind（UnfreezeRelayService）：bind 经 AMS/SmartPower
 *   uid 1000 服务调用规则触发 THAW / 冷启动，模块无须保活——解冻的执行者是系统。
 *
 * 唤醒目标进程的手段=模块 su 直写 freeze=0（UnfreezeBridge → UnfreezeRelayService），
 * 不杀任何进程，目标 App 状态零损失（kill 预热制已按用户要求退役）。
 *
 * 预热位：fan 呼出后 ~150ms 对快捷栏内 QS_TILE 目标逐个「su 解冻 + 预 bind（只 prime
 * 不点击）」——AMS 拉起/解冻全新进程、TileService 连接就绪，用户点击时直连零延迟。
 * 3s 内解冻过（UnfreezeBridge 节流）则跳过 su，只补 prebind。
 * 局限：freezer 数十秒后重冻，预热窗口只覆盖「呼出→点击」几秒；超窗由点击时
 * DirectLaunchStrategy 里的 unfreezeBlocking 兜底（同一节流表）。
 */
object FanPrewarmer {

    private const val TAG = "FanPrewarm"

    /** fan 展开动画首帧之后再触发，规避 fork/Application init 的 CPU 尖峰干扰动画 */
    private const val PREWARM_DELAY_MS = 150L

    /** AllApps 图标预灌数量（首屏 + 余量；全量预灌由 AllAppsActivity 打开后自己继续） */
    private const val ICON_PRELOAD_COUNT = 24

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "FanPrewarmer").apply { isDaemon = true }
    }

    /**
     * fan 呼出后预热入口（仅 :ui 的 DirectLaunchStrategy 调用，主线程安全立即返回）。
     * @param tileTargets QS_TILE 项的 (目标包名, 扁平组件名"pkg/cls") 对（去重后）——
     *   解冻按包名、预 bind 按组件名（hook 接收端 unflattenFromString 对裸包名返回 null）
     * @param fanAppPkgs 扇形应用图标包名（不含 ALL_APPS 哨兵），用于图标缓存预灌
     * @param token      remotePrefs 令牌（relay 防伪，与点击同款）
     */
    fun onFanShown(context: Context, tileTargets: List<Pair<String, String>>, fanAppPkgs: List<String>, token: String?) {
        val appCtx = context.applicationContext
        // 图标预热：扇形固定应用优先 + AllApps 首屏（进程级 LruCache ≈2-3MB，无进程语义）
        val iconPkgs = (fanAppPkgs.asSequence() + DataLoader.loadApps(appCtx).asSequence())
            .distinct().take(ICON_PRELOAD_COUNT).toList()
        AppIconCache.preload(appCtx, iconPkgs)
        if (tileTargets.isEmpty()) return
        executor.execute {
            runCatching {
                Thread.sleep(PREWARM_DELAY_MS)
                tileTargets.forEach { (pkg, cn) -> prewarmTile(appCtx, pkg, cn, token) }
            }.onFailure { Log.w(TAG, "prewarm loop failed: ${it.message}") }
        }
    }

    /** 单磁贴预热：su 解冻（30s 节流内跳过）→ 预 bind（只 prime 不点击）。后台线程调用。 */
    private fun prewarmTile(context: Context, pkg: String, cn: String, token: String?) {
        UnfreezeBridge.unfreezeBlocking(context, pkg, token)
        val served = QsTileClickBridge.sendPrebindBlocking(context, cn, token)
        Log.i(TAG, "prewarm: pkg=$pkg cn=$cn prebind=$served")
    }
}
