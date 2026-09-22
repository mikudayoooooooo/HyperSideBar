package com.lsp.hypersidebar.ui.fan

import android.content.Context
import com.lsp.hypersidebar.util.DismissCause
import com.lsp.hypersidebar.util.FanChannel
import com.lsp.hypersidebar.util.Trace
import android.content.SharedPreferences
import android.util.Log
import android.view.MotionEvent
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.AppMetaCache
import com.lsp.hypersidebar.util.DataLoader
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.StatsRecorder

private const val TAG = "FanMenuController"

/**
 * 驱动源看门狗超时（1C §3）：fan 展示中 N 秒无任何触摸事件 → 自收起 + 计数。
 * 手指按住不动时 MOVE 不再产生（输入系统只在移动时投递），10s 恒静止仍指向图标的
 * 场景不现实；而 0.x"fan 常驻不可撤回"的症状本质是事件流断供（无人再送 UP），
 * 收起路径全部依赖驱动源存活，看门狗是唯一不依赖它的兜底。
 */
private const val FAN_IDLE_TIMEOUT_MS = 10_000L

/**
 * 扇形菜单编排器：数据组装 + ComposeFanHost 装配 + 选中回调接线。
 * 进程无关（securitycenter:ui 与 com.miui.home 共用），执行动作经 [FanLaunchStrategy] 差异化。
 * [onMechanismResult] = 机制性结果上报（熔断器数据源，1C 轮二）：show 成功/失败各报一次，
 * 仅此两类——单应用启动失败属数据面，不在此报。
 */
class FanMenuController(
    private val prefs: SharedPreferences,
    private val launchStrategy: FanLaunchStrategy,
    private val onMechanismResult: ((success: Boolean, reason: String) -> Unit)? = null
) {

    // prefs 实例由装配方提供（hook 进程为 SyncedPrefs 同步感知装饰器——
    // 配置同步广播缓存命中优先，设置页写入即时可见；见 util/ConfigSync）

    @Volatile
    var isShowing = false
        private set
    private var host: ComposeFanHost? = null

    // ===== 数据记录（§11.3） =====
    private var showStartElapsed = 0L
    @Volatile private var exitAfterLaunch = false

    // 池=1（1C P2）：dismiss 后 host 不销毁，idleHost 持有供下次呼出复用；
    // activeContext = 最近一次 showInternal 的 context（回调经它取，见 obtainHost）
    private var idleHost: ComposeFanHost? = null
    private var activeContext: Context? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ===== 驱动源看门狗（1C §3） =====
    private var lastTouchElapsedMs = 0L
    private var watchdogFires = 0
    private val watchdogRunnable = Runnable {
        if (!isShowing) return@Runnable
        val idleMs = android.os.SystemClock.elapsedRealtime() - lastTouchElapsedMs
        if (idleMs < FAN_IDLE_TIMEOUT_MS) return@Runnable
        // 触摸流断供：手势中止/事件链断裂，UP 收起与滑回重置都不再有人驱动——
        // 0.x"fan 常驻"症状的最后防线（唯一不依赖事件流的收起路径）
        watchdogFires++
        HLog.w(TAG, "idle watchdog: no touch for ${idleMs}ms, self-dismiss (fire #$watchdogFires)")
        // 口径 v2 里"系统强制收起"只有这一条可识别路径（来电/切窗/冻结无事件源，统计页标注盲区）
        dismiss(DismissCause.WATCHDOG)
    }

    private fun touchHeartbeat() {
        lastTouchElapsedMs = android.os.SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, FAN_IDLE_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    fun show(context: Context, anchorX: Float, anchorY: Float, cornerAnchor: Boolean = false) {
        // hook 的触摸回调可能不在主线程（launcher 的 GestureStubView.onTouchEvent 经
        // MiuiMirror 输入线程分发，实测 tid≠主线程）；Compose 生命周期装配必须主线程
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            HLog.i(TAG, "show: hopping to main thread (from ${Thread.currentThread().name})")
            mainHandler.post { showInternal(context, anchorX, anchorY, cornerAnchor) }
        } else {
            showInternal(context, anchorX, anchorY, cornerAnchor)
        }
    }

    private fun showInternal(
        context: Context,
        anchorX: Float,
        anchorY: Float,
        cornerAnchor: Boolean = false
    ) {        // 实测轮七：入口状态遥测——定位 isShowing 被无日志翻转的路径（双开根因）
        Trace.current = Trace.new()
        HLog.i(TAG, tl() + "showInternal enter: isShowing=$isShowing host=${host != null} anchor=($anchorX,$anchorY)")
        if (isShowing && host != null) return
        // 耗时锚点（呼出卡顿归因）：cost=本次呼出主线程装配全程；firstAssembly=true
        // =本进程池为空（进程冷启/被杀后首呼出），这是"有时候呼出会卡"的头号嫌疑段
        val t0 = android.os.SystemClock.elapsedRealtime()

        isShowing = true
        // 防御性单窗口不变量：任何状态下不允许两个 fan 窗口并存——
        // 若 isShowing 已被异常翻回 false 而旧 host 仍存活，先拆除再建新
        host?.let { stale ->
            HLog.w(TAG, "showInternal: orphan host detected, tearing down")
            runCatching { stale.dismiss() }
        }
        host = null
        HLog.i(TAG, "show: anchor=($anchorX, $anchorY)")

        try {
            val isLandscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE

            val data = assembleFanData(context, cornerAnchor)

            // 池=1 复用（1C P2）：host 不逐呼出重建，context 经 activeContext 提供
            activeContext = context
            val firstAssembly = idleHost == null
            val fanHost = obtainHost()
            host = fanHost
            fanHost.show(anchorX, anchorY, data.apps, data.allQuick, isLandscape, cornerAnchor)
            touchHeartbeat()
            // 数据记录（§11.3）：呼出次数/响应时间/两次呼出间隔；通道与 assembleFanData 同构派生
            exitAfterLaunch = false
            showStartElapsed = android.os.SystemClock.elapsedRealtime()
            StatsRecorder.onFanShown(
                when {
                    cornerAnchor -> FanChannel.CORNER
                    isLandscape -> FanChannel.STRIP
                    else -> FanChannel.EDGE
                }
            )
            // 呼出即预热（2026-09-07 预热制）：QS_TILE 目标包 kill+预 bind、图标缓存预灌。
            // 策略差异：仅 :ui 的 DirectLaunchStrategy 覆写有动作，launcher 空实现
            launchStrategy.onFanShown(
                context,
                data.runtimeQuick,
                data.apps.map { it.packageName }.filter { it != ALL_APPS_PKG }
            )
            onMechanismResult?.invoke(true, "show ok")
            HLog.i(TAG, tl() + "show: fan overlay added (pooled), ${data.allQuick.size} quick actions, landscape=$isLandscape, " +
                "cost=${android.os.SystemClock.elapsedRealtime() - t0}ms, firstAssembly=$firstAssembly")

        } catch (e: Throwable) {
            HLog.e(TAG, tl() + "show FAILED: ${e.message}", e)
            isShowing = false
            host = null
            evictIdleHost()
            onMechanismResult?.invoke(false, "show failed: ${e.message}")
        }
    }

    /** [assembleFanData] 产物：扇形列表+快捷栏渲染列表+快捷栏原始动作（onFanShown 预热用）。 */
    private data class FanData(
        val apps: List<FanAppInfo>,
        val allQuick: List<FanAppInfo>,
        val runtimeQuick: List<com.lsp.hypersidebar.util.ShortcutAction>,
        val isLandscape: Boolean
    )

    /**
     * 数据组装（呼出与空闲预热共用，主线程调用）：配置读取+固定应用/推荐合并+哨兵+快捷栏。
     * label 经 AppMetaCache（init 时 preloadConfiguredFanIcons 已盘灌+预热固定项）。
     */
    private fun assembleFanData(context: Context, cornerAnchor: Boolean = false): FanData {
        val isLandscape = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // 数量键按呼出形态取：底角用底角独立键（此前误用竖屏键——底角 sheet 调数量不生效）
        val (maxOuter, maxInner) = when {
            cornerAnchor ->
                readPref(PrefKeys.CORNER_MAX_APPS_OUTER, LayoutDefaults.CORNER_MAX_APPS_OUTER) to
                    readPref(PrefKeys.CORNER_MAX_APPS_INNER, LayoutDefaults.CORNER_MAX_APPS_INNER)
            isLandscape ->
                readPref(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER) to
                    readPref(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
            else ->
                readPref(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER) to
                    readPref(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER)
        }

        val customApps = readStringSetPref(PrefKeys.CUSTOM_APPS, emptySet())
        // 已选固定应用的用户排序（§2.4 拖动排序）：CUSTOM_APPS_ORDER 为权威，
        // 缺失项（旧数据/未排序）排在有序项之后
        val customOrder = readCustomAppsOrder()
        val orderedCustom = if (customOrder.isEmpty()) {
            customApps
        } else {
            customApps.sortedBy { pkg ->
                customOrder.indexOf(pkg).let { if (it >= 0) it else Int.MAX_VALUE }
            }
        }

        val allSystemApps = DataLoader.loadApps(context)
        val merged = LinkedHashSet<String>()
        merged.addAll(orderedCustom)
        merged.addAll(allSystemApps)
        // 圈内末位常驻"全部应用"入口（PRD §7.3.2）：合并列表截到（总数-1）留出末位，
        // 哨兵项计入 7+4 参与正常环布局；合并列表为空时扇形单独承载哨兵（不再中止呼出）
        val apps = merged.take((maxOuter + maxInner - 1).coerceAtLeast(0))
            .map { pkg -> FanAppInfo(pkg, AppMetaCache.label(context, pkg)) } +
            FanAppInfo(ALL_APPS_PKG, "全部应用")

        // 快捷栏单一来源：面板占位（第一位，可用时）+ 用户启用的 shortcut_actions（PRD §7.1）
        val runtimeQuick = ShortcutStore.buildRuntimeQuickList(
            prefs,
            ShortcutStore.isToolboxAvailable(context),
            ShortcutStore.getToolboxLabel(context)
        )
        val allQuick = runtimeQuick.map { sa ->
            FanAppInfo(
                // 真实宿主包名（批次 2 修复）：此前用 "shortcut:${id}" 伪包名，
                // AppIconCache.load 的 getApplicationIcon 必然 NameNotFound → bitmap=null
                // → 快捷栏退化字首头像（圆形）而非宿主真图标。启动逻辑不依赖
                // packageName（走 actionHandle 闭包捕获的 sa），改真包名无副作用
                packageName = sa.packageName ?: "",
                appName = sa.label,
                actionHandle = { ctx ->
                    if (sa.kind == ShortcutKind.TOOLBOX) {
                        launchStrategy.openNativePanel(ctx)
                    } else {
                        launchStrategy.launchShortcut(ctx, sa)
                    }
                }
            )
        }
        return FanData(apps, allQuick, runtimeQuick, isLandscape)
    }

    /**
     * 空闲预热装配（2026-09-13 横屏游戏首呼出动画卡顿定案）：首呼出的"首次 composition+
     * 主题解析+内容树组合+首帧绘制"是一次性大成本（项目实测首装配 ~250-300ms 主线程块，
     * 游戏场景 GPU/CPU 争用下更糟）——挪到进程 init 空闲期执行。1×1 离屏窗口（不可见、
     * NOT_TOUCHABLE）完成试装配后即摘窗，池内 composition 保留；真呼出走 built=true
     * 快路径。数据用真实配置列表（顺带热 label/图标路径），任意线程可调（自跳主线程）。
     */
    fun warmupAssembly(context: Context) {
        if (isShowing || idleHost != null) return
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { warmupAssembly(context) }
            return
        }
        runCatching {
            val data = assembleFanData(context)
            activeContext = context
            obtainHost().warmup(context, data.apps, data.allQuick)
        }.onFailure { HLog.w(TAG, tl() + "warmupAssembly failed: ${it.message}") }
    }

    fun hideAll() {
        dismiss()
    }

    /**
     * 池=1 复用（1C P2）：host 的 composition/lifecycle/视图树跨呼出存活，dismiss 只摘窗口。
     * 回调经 [activeContext] 取上下文——池化后 host 不逐呼出重建，不能闭包捕获单次
     * showInternal 的 context 参数。
     */
    private fun obtainHost(): ComposeFanHost {
        idleHost?.let { return it }
        val ctx = activeContext ?: throw IllegalStateException("activeContext missing")
        return ComposeFanHost(ctx, prefs).apply {
            onAppSelected = { appInfo ->
                HLog.i(TAG, tl() + "onAppSelected: ${appInfo.packageName}")
                val context = activeContext
                if (context != null) {
                    val isAllApps = appInfo.packageName == ALL_APPS_PKG
                    // 数据记录（§11.3）：打开次数/全部应用次数/选择时长
                    StatsRecorder.onOpen(
                        isAllApps,
                        (android.os.SystemClock.elapsedRealtime() - showStartElapsed).toInt()
                    )
                    exitAfterLaunch = true
                    if (isAllApps) {
                        launchStrategy.launchAllApps(context)
                    } else {
                        launchStrategy.launchFreeform(context, appInfo.packageName)
                    }
                    hideAll()
                }
            }

            onQuickAppSelected = { appInfo ->
                HLog.i(TAG, tl() + "onQuickAppSelected: ${appInfo.packageName}")
                val context = activeContext
                if (context != null) {
                    if (appInfo.actionHandle != null) {
                        StatsRecorder.onShortcut()
                        exitAfterLaunch = true
                        appInfo.actionHandle.invoke(context)
                        dismiss()
                    } else {
                        // 防御分支：快捷项理论上均携带 actionHandle
                        launchStrategy.launchFreeform(context, appInfo.packageName)
                    }
                }
            }

            onDismiss = {
                Log.d(TAG, "fanMenu onDismiss callback")
                isShowing = false
                host = null
            }
            idleHost = this
        }
    }

    /** show 失败弃池：host 可能半坏，全量销毁，下次呼出重建。 */
    private fun evictIdleHost() {
        idleHost?.let { h ->
            idleHost = null
            runCatching { h.destroy() }
                .onFailure { HLog.w(TAG, "idle host destroy failed: ${it.message}") }
        }
    }

    /** 触摸事件转发给扇形菜单；返回 false 表示当前无菜单。跨线程安全（自动跳主线程）。 */
    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing) return false
        touchHeartbeat()
        // 必须捕获局部引用：跨线程路径下 dismiss() 会在 post 之后立即置空 host，
        // 若 lambda 里读字段，转发的 UP 会被静默丢弃（实测 4 次呼出 0 次 UP 送达）
        val h = host ?: return false
        val copy = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try { h.dispatchTouchEvent(copy) } finally { copy.recycle() }
        } else {
            // launcher 的触摸回调在 MiuiMirror 输入线程，Compose 视图操作必须主线程
            mainHandler.post {
                try { h.dispatchTouchEvent(copy) } finally { copy.recycle() }
            }
        }
        return true
    }

    /**
     * 收起扇形。状态机字段（isShowing/host）只允许主线程读写：
     * launcher 输入线程的 UP 若直接清字段，会与正在执行 showInternal 的主线程竞态
     * （冷路径 getFreeformSuggestionList 可阻塞数百 ms）——"装配中"的 show 完成后
     * host 指向孤儿窗口且 isShowing=false，扇形常驻的同时还能再唤出第二个。
     * 改投递语义串行化：show Runnable 先入队、dismiss 后入队 ⇒ 必然先完整展示再拆除
     * （对应 PRD"未预选松手立即收起"），正确性与主线程阻塞时长无关。
     */
    fun dismiss(cause: DismissCause = DismissCause.USER_UP) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            doDismiss("main", cause)
        } else {
            HLog.i(TAG, tl() + "dismiss: posted from ${Thread.currentThread().name}")
            mainHandler.post { doDismiss("posted", cause) }
        }
    }

    private fun doDismiss(via: String, cause: DismissCause) {
        cancelWatchdog()
        val h = host
        if (h == null) {
            isShowing = false
            return
        }
        HLog.i(TAG, tl() + "doDismiss($via,$cause): requesting host exit")
        // 数据记录（§11.3 采集层 v2）：收起原因透传进逐事件流水；
        // 启动后的自动退出以 exitAfterLaunch 为准（覆盖调用方传的 USER_UP）
        StatsRecorder.onFanClosed(if (exitAfterLaunch) DismissCause.LAUNCHED else cause)
        exitAfterLaunch = false
        // 摘除时序（2026-09-13 收拢动画改造）：host.dismiss() 请求收拢动画（内部即置
        // FLAG_NOT_TOUCHABLE=触摸零拦截）后立即返回，物理摘窗由收拢完成回调执行
        // （世代守卫防"收拢中再呼出"双窗；兜底定时器防回调丢失）。本侧状态位立即清
        // =交互即死：isShowing=false 后事件不再转发，池化 host 由 idleHost 继续持有
        try {
            h.dismiss()
        } catch (e: Throwable) {
            HLog.w(TAG, "host.dismiss threw: ${e.message}")
        }
        isShowing = false
        host = null
    }

    fun getStats(): String =
        "fanMenu=${host != null}, isShowing=$isShowing, watchdogFires=$watchdogFires"

    /** 呼出链路追踪前缀（util/Trace）：单次呼出全程同一 id，跨进程靠 intent extra 传递 */
    private fun tl(): String = "[${Trace.current ?: "-"}] "

    private fun readPref(key: String, default: Float): Float {
        return try { prefs.getFloat(key, default) } catch (_: Exception) { default }
    }

    private fun readPref(key: String, default: Int): Int {
        return try { prefs.getInt(key, default) } catch (_: Exception) { default }
    }

    private fun readStringSetPref(key: String, default: Set<String>): Set<String> {
        return try { prefs.getStringSet(key, default) ?: default } catch (_: Exception) { default }
    }

    /** 已选固定应用顺序（JSON 数组字符串，设置页拖动排序写入）。 */
    private fun readCustomAppsOrder(): List<String> {
        val json = try {
            prefs.getString(PrefKeys.CUSTOM_APPS_ORDER, null)
        } catch (_: Exception) { null } ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (_: Exception) { emptyList() }
    }
}
