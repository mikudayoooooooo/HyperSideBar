package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.MotionEvent
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.AppMetaCache
import com.lsp.hypersidebar.util.DataLoader
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore

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

    @Volatile
    var isShowing = false
        private set
    private var host: ComposeFanHost? = null

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
        Log.w(TAG, "idle watchdog: no touch for ${idleMs}ms, self-dismiss (fire #$watchdogFires)")
        dismiss()
    }

    private fun touchHeartbeat() {
        lastTouchElapsedMs = android.os.SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, FAN_IDLE_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    fun show(context: Context, anchorX: Float, anchorY: Float) {
        // hook 的触摸回调可能不在主线程（launcher 的 GestureStubView.onTouchEvent 经
        // MiuiMirror 输入线程分发，实测 tid≠主线程）；Compose 生命周期装配必须主线程
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Log.i(TAG, "show: hopping to main thread (from ${Thread.currentThread().name})")
            mainHandler.post { showInternal(context, anchorX, anchorY) }
        } else {
            showInternal(context, anchorX, anchorY)
        }
    }

    private fun showInternal(context: Context, anchorX: Float, anchorY: Float) {
        // 实测轮七：入口状态遥测——定位 isShowing 被无日志翻转的路径（双开根因）
        Log.i(TAG, "showInternal enter: isShowing=$isShowing host=${host != null} anchor=($anchorX,$anchorY)")
        if (isShowing && host != null) return

        isShowing = true
        // 防御性单窗口不变量：任何状态下不允许两个 fan 窗口并存——
        // 若 isShowing 已被异常翻回 false 而旧 host 仍存活，先拆除再建新
        host?.let { stale ->
            Log.w(TAG, "showInternal: orphan host detected, tearing down")
            runCatching { stale.dismiss() }
        }
        host = null
        Log.i(TAG, "show: anchor=($anchorX, $anchorY)")

        try {
            val isLandscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE

            val (maxOuter, maxInner) = if (isLandscape) {
                readPref(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER) to
                    readPref(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
            } else {
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
                    packageName = if (sa.kind == ShortcutKind.TOOLBOX) "__open_panel__" else "shortcut:${sa.id}",
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

            // 池=1 复用（1C P2）：host 不逐呼出重建，context 经 activeContext 提供
            activeContext = context
            val fanHost = obtainHost()
            host = fanHost
            fanHost.show(anchorX, anchorY, apps, allQuick, isLandscape)
            touchHeartbeat()
            onMechanismResult?.invoke(true, "show ok")
            Log.i(TAG, "show: fan overlay added (pooled), ${allQuick.size} quick actions, landscape=$isLandscape")

        } catch (e: Throwable) {
            Log.e(TAG, "show FAILED: ${e.message}", e)
            isShowing = false
            host = null
            evictIdleHost()
            onMechanismResult?.invoke(false, "show failed: ${e.message}")
        }
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
                Log.i(TAG, "onAppSelected: ${appInfo.packageName}")
                val context = activeContext
                if (context != null) {
                    if (appInfo.packageName == ALL_APPS_PKG) {
                        launchStrategy.launchAllApps(context)
                    } else {
                        launchStrategy.launchFreeform(context, appInfo.packageName)
                    }
                    hideAll()
                }
            }

            onQuickAppSelected = { appInfo ->
                Log.i(TAG, "onQuickAppSelected: ${appInfo.packageName}")
                val context = activeContext
                if (context != null) {
                    if (appInfo.actionHandle != null) {
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
                .onFailure { Log.w(TAG, "idle host destroy failed: ${it.message}") }
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
    fun dismiss() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            doDismiss("main")
        } else {
            Log.i(TAG, "dismiss: posted from ${Thread.currentThread().name}")
            mainHandler.post { doDismiss("posted") }
        }
    }

    private fun doDismiss(via: String) {
        cancelWatchdog()
        val h = host
        if (h == null) {
            isShowing = false
            return
        }
        Log.i(TAG, "doDismiss($via): tearing down host")
        // 强一致（1C §3）：先完成视图真实摘除，再清状态位——顺序颠倒会把
        // "视图还活着"伪装成"已收起"，下次呼出在旧窗口之上再叠一个（双开根因）
        try {
            h.dismiss()
        } catch (e: Throwable) {
            Log.w(TAG, "host.dismiss threw: ${e.message}")
        }
        isShowing = false
        host = null
    }

    fun getStats(): String =
        "fanMenu=${host != null}, isShowing=$isShowing, watchdogFires=$watchdogFires"

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
